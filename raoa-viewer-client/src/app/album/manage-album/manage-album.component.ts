import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {ServerApiService} from '../../service/server-api.service';
import {
    CreateGroupGQL,
    LabelInput,
    ManageAlbumUpdateGQL,
    ManageAlbumUpdateMutationVariables,
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
import {FNCH_COMPETITION_ID} from '../../constants';


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
    public selectedGroups: Set<string> = new Set();
    private activeGroups: Set<string> = new Set();
    public selectedUsers: Set<string> = new Set();
    private activeUsers: Set<string> = new Set();
    fnchCompetitionId: string;

    constructor(private activatedRoute: ActivatedRoute,
                private serverApi: ServerApiService,
                private queryAlbumSettingsGQL: QueryAlbumSettingsGQL,
                private createGroupGQL: CreateGroupGQL,
                private updateCredentialsGQL: UpdateCredentitalsGQL,
                private manageAlbumUpdateGQL: ManageAlbumUpdateGQL,
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
            const activeGroups = data.albumById.canAccessedByGroup.map(u => u.id);
            this.activeGroups = new Set<string>(activeGroups);
            if (this.selectedGroups.size === 0) {
                this.selectedGroups = new Set(activeGroups);
            }

            const activeUsers = data.albumById.canAccessedByUser.map(u => u.id);
            this.activeUsers = new Set(activeUsers);
            if (this.selectedUsers.size === 0) {
                this.selectedUsers = new Set(activeUsers);
            }
            const labels: Map<string, string> = new Map<string, string>();
            data.albumById.labels.forEach(lv => {
                labels.set(lv.labelName, lv.labelValue);
            });
            this.fnchCompetitionId = labels.get(FNCH_COMPETITION_ID);
            loadingElement.dismiss();
        });
    }

    back() {
        this.location.back();
    }


    async store() {
        const userUpdates: SingleUserVisibilityUpdate[] = [];
        this.selectedUsers.forEach(uid => {
            if (!this.activeUsers.has(uid)) {
                userUpdates.push({albumId: this.albumId, userId: uid, isMember: true});
            }
        });
        this.activeUsers.forEach(uid => {
            if (!this.selectedUsers.has(uid)) {
                userUpdates.push({albumId: this.albumId, userId: uid, isMember: false});
            }
        });
        const groupUpdates: SingleGroupVisibilityUpdate[] = [];
        this.selectedGroups.forEach(gid => {
            if (!this.activeGroups.has(gid)) {
                groupUpdates.push({albumId: this.albumId, groupId: gid, isMember: true});
            }
        });
        this.activeGroups.forEach(gid => {
            if (!this.selectedGroups.has(gid)) {
                groupUpdates.push({albumId: this.albumId, groupId: gid, isMember: false});
            }
        });
        const data: UpdateCredentitalsMutationVariables = {
            update: {
                userUpdates,
                groupUpdates,
                groupMembershipUpdates: []

            }
        };
        await this.serverApi.update(this.updateCredentialsGQL, data);
        const newAlbumTitle: string | null = null;
        const newTitleEntry: string | null = null;
        const newLabels: LabelInput[] = [];
        const removeLabels: string[] = [];
        if (this.fnchCompetitionId && this.fnchCompetitionId.trim().length > 0) {
            newLabels.push({labelName: FNCH_COMPETITION_ID, labelValue: this.fnchCompetitionId.trim()});
        } else {
            removeLabels.push(FNCH_COMPETITION_ID);
        }
        const albumUpdate: ManageAlbumUpdateMutationVariables = {
            id: this.albumId,
            update: {
                newAlbumTitle,
                newLabels,
                newTitleEntry,
                removeLabels
            }
        };
        await this.serverApi.update(this.manageAlbumUpdateGQL, albumUpdate);
        await this.serverApi.clear();
        await this.refreshData();
    }

    public groupChanged($event: Set<string>) {
        this.selectedGroups = $event;
    }

    public usersChanged($event: Set<string>) {
        this.selectedUsers = $event;
    }
}
