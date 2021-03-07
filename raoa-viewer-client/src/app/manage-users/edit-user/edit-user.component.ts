import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {
    EditUserOverviewGQL,
    SingleGroupMembershipUpdate,
    SingleGroupVisibilityUpdate,
    SingleUserVisibilityUpdate,
    UpdateCredentitalsGQL,
    UpdateCredentitalsMutationVariables,
    User
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
    public selectedAlbums: Set<string> = new Set();
    private originalSelectedAlbums: Set<string> = new Set();
    public user: User;

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
            this.user = data.userById as User;
            const groups = this.user.groups.map(g => g.group.id);
            this.selectedGroups = new Set(groups);
            this.originalSelectedGroups = new Set<string>(groups);
            const albums = this.user.canAccessDirect.map(a => a.id);
            this.selectedAlbums = new Set<string>(albums);
            this.originalSelectedAlbums = new Set<string>(albums);

        });
    }

    public async apply() {
        const userUpdates: SingleUserVisibilityUpdate[] = [];
        this.selectedAlbums.forEach(id => {
            if (!this.originalSelectedAlbums.has(id)) {
                userUpdates.push({albumId: id, userId: this.userId, isMember: true});
            }
        });
        this.originalSelectedAlbums.forEach(id => {
            if (!this.selectedAlbums.has(id)) {
                userUpdates.push({albumId: id, userId: this.userId, isMember: false});
            }
        });
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
