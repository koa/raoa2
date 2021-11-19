import {Component, NgZone, OnInit} from '@angular/core';
import {ServerApiService} from '../../service/server-api.service';
import {
    Album,
    EditGroupLoadGQL,
    EditGroupUpdateGroupGQL,
    EditGroupUpdateGroupMutationVariables,
    Group,
    Label,
    LabelInput,
    Maybe,
    SingleGroupMembershipUpdate,
    SingleGroupVisibilityUpdate,
    SingleUserVisibilityUpdate,
    UpdateCredentitalsGQL,
    UpdateCredentitalsMutationVariables,
    User,
    UserMembership
} from '../../generated/graphql';
import {ActivatedRoute} from '@angular/router';
import {FNCH_COMPETITOR_ID} from '../../constants';


@Component({
    selector: 'app-edit-group',
    templateUrl: './edit-group.component.html',
    styleUrls: ['./edit-group.component.scss'],
})
export class EditGroupComponent implements OnInit {
    public selectedAlbums: Set<string> = new Set();
    public selectedUsers: Set<string> = new Set();
    private groupId: string;
    public groupName: string;
    public groupData: Maybe<(
        { __typename?: 'Group' }
        & Pick<Group, 'id' | 'name'>
        & {
        members: Array<(
            { __typename?: 'UserMembership' }
            & Pick<UserMembership, 'from' | 'until'>
            & {
            user: ({ __typename?: 'User' } & Pick<User, 'id'>)
        })>,
        canAccess: Array<({ __typename?: 'Album' } & Pick<Album, 'id'>)>,
        labels: Array<({ __typename?: 'Label' } & Pick<Label, 'labelName' | 'labelValue'>)>
    }
        )>;
    public fnchCompetitorId: string | null;
    public visibleTab: 'details' | 'users' | 'albums' = 'details';


    constructor(private activatedRoute: ActivatedRoute,
                private serverApiService: ServerApiService,
                private editGroupLoadGQL: EditGroupLoadGQL,
                private updateCredentitalsGQL: UpdateCredentitalsGQL,
                private editGroupUpdateGroupGQL: EditGroupUpdateGroupGQL,
                private ngZone: NgZone
    ) {
    }

    async ngOnInit() {
        this.groupId = this.activatedRoute.snapshot.paramMap.get('id');
        await this.refreshData();
    }

    private async refreshData() {
        const groupData = (await this.serverApiService.query(this.editGroupLoadGQL, {id: this.groupId}))?.groupById;
        if (groupData) {
            this.ngZone.run(() => {
                this.groupName = groupData.name;
                if (this.selectedUsers.size === 0) {
                    groupData.members.forEach(user => {
                        this.selectedUsers.add(user.user.id);
                    });
                }

                if (this.selectedAlbums.size === 0) {
                    groupData.canAccess.forEach(album => {
                        this.selectedAlbums.add(album.id);
                    });
                }
                const labels: Map<string, string> = new Map<string, string>();
                groupData.labels.forEach(lv => {
                    labels.set(lv.labelName, lv.labelValue);
                });
                this.fnchCompetitorId = labels.get(FNCH_COMPETITOR_ID);

                this.groupData = groupData;
            });
        }
    }

    albumsChanged($event: Set<string>) {
        this.selectedAlbums = $event;
    }

    usersChanged($event: Set<string>) {
        this.selectedUsers = $event;
    }

    async apply() {
        const userUpdates: Array<SingleUserVisibilityUpdate> = [];
        const groupUpdates: Array<SingleGroupVisibilityUpdate> = [];
        const groupMembershipUpdates: Array<SingleGroupMembershipUpdate> = [];
        const previousUsers = new Set(this.groupData.members.map(user => user.user.id));
        this.selectedUsers.forEach(id => {
            if (!previousUsers.has(id)) {
                groupMembershipUpdates.push({userId: id, groupId: this.groupId, isMember: true});
            }
        });
        previousUsers.forEach(id => {
            if (!this.selectedUsers.has(id)) {
                groupMembershipUpdates.push({userId: id, groupId: this.groupId, isMember: false});
            }
        });
        const previousAlbums = new Set(this.groupData.canAccess.map(album => album.id));
        this.selectedAlbums.forEach(id => {
            if (!previousAlbums.has(id)) {
                groupUpdates.push({groupId: this.groupId, albumId: id, isMember: true});
            }
        });
        previousAlbums.forEach(id => {
            if (!this.selectedAlbums.has(id)) {
                groupUpdates.push({groupId: this.groupId, albumId: id, isMember: false});
            }
        });
        if (userUpdates.length !== 0 || groupUpdates.length !== 0 || groupMembershipUpdates.length !== 0) {
            const updateRequest: UpdateCredentitalsMutationVariables = {
                update: {
                    groupMembershipUpdates,
                    groupUpdates,
                    userUpdates
                }
            };
            await this.serverApiService.update(this.updateCredentitalsGQL, updateRequest);
        }
        const newLabels: LabelInput[] = [];
        const removeLabels: string[] = [];
        if (this.fnchCompetitorId && this.fnchCompetitorId.trim().length > 0) {
            newLabels.push({labelName: FNCH_COMPETITOR_ID, labelValue: this.fnchCompetitorId.trim()});
        } else {
            removeLabels.push(FNCH_COMPETITOR_ID);
        }
        const update: EditGroupUpdateGroupMutationVariables = {
            id: this.groupId,
            update: {
                newName: this.groupName,
                newLabels,
                removeLabels
            }
        };
        await this.serverApiService.update(this.editGroupUpdateGroupGQL, update);
        await this.serverApiService.clear();
        await this.refreshData();
    }
}
