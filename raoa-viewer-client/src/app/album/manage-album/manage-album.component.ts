import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {ServerApiService} from '../../service/server-api.service';
import {
    CreateGroupGQL,
    Group,
    Maybe,
    QueryAlbumSettingsGQL,
    SingleGroupVisibilityUpdate,
    SingleUserVisibilityUpdate,
    UpdateCredentitalsGQL,
    UpdateCredentitalsMutationVariables,
    User,
    UserInfo
} from '../../generated/graphql';
import {LoadingController, ToastController} from '@ionic/angular';
import {Location} from '@angular/common';

type GroupDataType = { __typename?: 'Group' } & Pick<Group, 'id' | 'name'>;

interface GroupEntry {
    selectedBefore: boolean;
    newSelected: boolean;
    data: GroupDataType;
}

type UserDataType =
    { __typename?: 'User' }
    & Pick<User, 'id'>
    & { info?: Maybe<{ __typename?: 'UserInfo' } & Pick<UserInfo, 'name' | 'email' | 'picture'>> };

interface UserEntry {
    selectedBefore: boolean;
    newSelected: boolean;
    data: UserDataType;
}

@Component({
    selector: 'app-manage-album',
    templateUrl: './manage-album.component.html',
    styleUrls: ['./manage-album.component.css'],
})
export class ManageAlbumComponent implements OnInit {
    public albumId: string;
    public albumName: string;
    public groups: GroupEntry[] = [];
    public filteredGroups: GroupEntry[] = [];
    public newGroupName = '';
    public users: UserEntry[] = [];
    private userFilter = '';
    public filteredUsers: UserEntry[] = [];
    private groupFilter = '';

    constructor(private activatedRoute: ActivatedRoute,
                private serverApi: ServerApiService,
                private queryAlbumSettingsGQL: QueryAlbumSettingsGQL,
                private createGroupGQL: CreateGroupGQL,
                private updateCredentitalsGQL: UpdateCredentitalsGQL,
                private ngZone: NgZone,
                private loadController: LoadingController,
                private toastController: ToastController,
                private location: Location
    ) {
    }

    async ngOnInit() {
        this.albumId = this.activatedRoute.snapshot.paramMap.get('id');
        await this.refreshData();
    }

    private async refreshData() {
        const loadingElement = await this.loadController.create({message: 'Daten werden geladen'});
        await loadingElement.present();
        const data = await this.serverApi.query(this.queryAlbumSettingsGQL, {albumId: this.albumId});
        if (!data) {
            await loadingElement.dismiss();
            return;
        }
        this.ngZone.run(() => {
            this.albumName = data.albumById.name;
            const activeGroups: Set<string> = new Set(data.albumById.canAccessedByGroup.map(g => g.id));
            const activeUsers: Set<string> = new Set(data.albumById.canAccessedByUser.map(u => u.id));

            const prevActivatedGroups = new Map<string, boolean>();
            this.groups.forEach(entry => prevActivatedGroups[entry.data.id] = entry.newSelected);
            this.groups = data.listGroups.map((group: GroupDataType) => ({
                selectedBefore: activeGroups.has(group.id),
                newSelected: prevActivatedGroups.has(group.id) ? prevActivatedGroups.get(group.id) : activeGroups.has(group.id),
                data: group
            })).sort((g1, g2) => g1.data.name.localeCompare(g2.data.name));
            const prevActivatedUsers = new Map<string, boolean>();
            this.users.forEach(entry => prevActivatedUsers[entry.data.id] = entry.newSelected);
            this.users = data.listUsers.map((user: UserDataType) => {
                const ret: UserEntry = {
                    selectedBefore: activeUsers.has(user.id),
                    newSelected: prevActivatedUsers.has(user.id) ? prevActivatedUsers.get(user.id) : activeUsers.has(user.id),
                    data: user
                };
                return ret;
            }).sort((u1, u2) => u1.data.info.name.localeCompare(u2.data.info.name));
            this.filterUser();
            this.filterGroup();
            loadingElement.dismiss();
        });
    }

    back() {
        this.location.back();
    }

    updateNewGroupName($event: CustomEvent) {
        this.newGroupName = $event.detail.value;
    }

    async createNewGroup() {
        const groupName = this.newGroupName;
        if (groupName.length === 0) {
            return;
        }
        if (this.groups.filter(g => g.data.name === groupName).length > 0) {
            return;
        }
        this.newGroupName = '';
        const loadingElement = await this.loadController.create({message: 'Erstelle Gruppe ' + groupName});
        await loadingElement.present();
        const result = await this.serverApi.update(this.createGroupGQL, {name: groupName});
        await loadingElement.dismiss();
        await this.refreshData();
    }

    searchUser($event: CustomEvent) {
        this.userFilter = $event.detail.value;
        this.filterUser();
    }

    private filterUser() {
        const pattern = this.userFilter.toLowerCase();
        this.filteredUsers = this.users
            .filter(e => e.data.info.name.toLowerCase().indexOf(pattern) >= 0 || e.data.info.email.toLowerCase().indexOf(pattern) >= 0);
    }

    searchGroup($event: CustomEvent) {
        this.groupFilter = $event.detail.value;
        this.filterGroup();

    }

    private filterGroup() {
        const pattern = this.groupFilter.toLowerCase();
        this.filteredGroups = this.groups
            .filter(e => e.data.name.toLowerCase().indexOf(pattern) >= 0);
    }

    async store() {
        const userUpdates: SingleUserVisibilityUpdate[] = [];
        this.users.forEach(userEntry => {
            if (userEntry.selectedBefore !== userEntry.newSelected) {
                userUpdates.push({albumId: this.albumId, userId: userEntry.data.id, isMember: userEntry.newSelected});
            }
        });
        const groupUpdates: SingleGroupVisibilityUpdate[] = [];
        this.groups.forEach(groupEntry => {
            if (groupEntry.selectedBefore !== groupEntry.newSelected) {
                groupUpdates.push({albumId: this.albumId, groupId: groupEntry.data.id, isMember: groupEntry.newSelected});
            }
        });
        const data: UpdateCredentitalsMutationVariables = {
            update: {
                userUpdates,
                groupUpdates,
                groupMembershipUpdates: []

            }
        };
        await this.serverApi.update(this.updateCredentitalsGQL, data);
        await this.serverApi.clear();
        await this.refreshData();
    }
}
