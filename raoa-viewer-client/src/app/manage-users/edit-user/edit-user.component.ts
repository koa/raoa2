import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {
    EditUserOverviewGQL,
    Group,
    GroupMembership,
    Maybe,
    SingleGroupMembershipUpdate,
    SingleGroupVisibilityUpdate,
    SingleUserVisibilityUpdate,
    UpdateCredentitalsGQL,
    UpdateCredentitalsMutationVariables,
    User,
    UserInfo
} from '../../generated/graphql';
import {ServerApiService} from '../../service/server-api.service';


@Component({
    selector: 'app-edit-user',
    templateUrl: './edit-user.component.html',
    styleUrls: ['./edit-user.component.scss'],
})
export class EditUserComponent implements OnInit {
    private userId: string;
    public selectedGroups: Set<string> = new Set();
    private originalSelectedGroups: Set<string> = new Set();
    public data: ({ __typename?: 'Query' } & {
        userById?: Maybe<{ __typename?: 'User' } &
            Pick<User, 'id' | 'canManageUsers'> & {
            info?: Maybe<{ __typename?: 'UserInfo' } &
                Pick<UserInfo, 'name' | 'email' | 'picture'>>;
            groups?: Maybe<Array<Maybe<{ __typename?: 'GroupMembership' } &
                Pick<GroupMembership, 'from' | 'until'> & { group: { __typename?: 'Group' } & Pick<Group, 'id'> }>>>
        }>
    }) | null;

    constructor(private activatedRoute: ActivatedRoute,
                private serverApi: ServerApiService,
                private editUserOverviewGQL: EditUserOverviewGQL,
                private serverApiService: ServerApiService,
                private updateCredentialsGQL: UpdateCredentitalsGQL,
                private ngZone: NgZone) {
    }

    async ngOnInit() {
        this.userId = this.activatedRoute.snapshot.paramMap.get('id');
        await this.refreshData();

    }

    private async refreshData() {
        const data = await this.serverApiService.query(this.editUserOverviewGQL, {userid: this.userId});
        this.ngZone.run(() => {
            this.data = data;
            const groups = this.data.userById.groups.map(g => g.group.id);
            console.log(groups);
            this.selectedGroups = new Set(groups);
            this.originalSelectedGroups = new Set<string>(groups);
        });
    }

    public async apply() {
        const userUpdates: SingleUserVisibilityUpdate[] = [];
        const groupUpdates: SingleGroupVisibilityUpdate[] = [];
        const groupMembershipUpdates: SingleGroupMembershipUpdate[] = [];
        this.originalSelectedGroups.forEach(gid => {
            if (!this.selectedGroups.has(gid)) {
                groupMembershipUpdates.push({
                    userId: this.userId,
                    isMember: false,
                    groupId: gid,
                    from: null,
                    until: null
                });
            }
        });
        this.selectedGroups.forEach(gid => {
            if (!this.originalSelectedGroups.has(gid)) {
                groupMembershipUpdates.push({
                    userId: this.userId,
                    isMember: true,
                    groupId: gid,
                    from: null,
                    until: null
                });
            }
        });
        const modified = userUpdates.length > 0 || groupUpdates.length > 0 || groupMembershipUpdates.length > 0;
        if (modified) {
            const data: UpdateCredentitalsMutationVariables = {
                update: {
                    userUpdates,
                    groupUpdates,
                    groupMembershipUpdates
                }
            };
            await this.serverApi.update(this.updateCredentialsGQL, data);
            await this.serverApi.clear();
        }
        await this.refreshData();

    }

    groupChanged($event: Set<string>) {
        this.selectedGroups = $event;
    }
}
