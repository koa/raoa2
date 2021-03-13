import {Component, NgZone, OnInit} from '@angular/core';
import {AlbumEntryDataType, CommonServerApiService, MenuEntry} from '../../service/common-server-api.service';
import {Location} from '@angular/common';
import {LoadingController} from '@ionic/angular';
import {FNCH_COMPETITION_ID} from '../../constants';
import {CanManageUsersGQL} from '../../generated/graphql';
import {ServerApiService} from '../../service/server-api.service';

@Component({
    selector: 'app-album-list',
    templateUrl: './album-list.component.html',
    styleUrls: ['./album-list.component.css'],
})
export class AlbumListComponent implements OnInit {
    private photoCollectionFilter: string;
    public foundAlbums: MenuEntry[];
    public canManageUsers = false;

    constructor(private commonServerApi: CommonServerApiService,
                private canManageUsersGQL: CanManageUsersGQL,
                private serverApi: ServerApiService,
                private ngZone: NgZone,
                private location: Location,
                private loadingController: LoadingController) {
    }

    async ngOnInit() {
        const canManageUsersResult = await this.serverApi.query(this.canManageUsersGQL, {});
        this.canManageUsers = canManageUsersResult.currentUser.canManageUsers;
        await this.updatePhotoCollectionList();
    }

    updateSearch(event: CustomEvent) {
        this.photoCollectionFilter = event.detail.value;
        this.updatePhotoCollectionList();
    }

    private async updatePhotoCollectionList() {
        const loadingElement = await this.loadingController.create({message: 'Daten werden geladen...'});
        await loadingElement.present();
        const entries = await this.commonServerApi.listCollections(this.photoCollectionFilter);
        this.ngZone.run(() => {
            this.foundAlbums = entries;
            loadingElement.dismiss();
        });
    }

    back() {
        this.location.back();
    }

    getFnchId(data: AlbumEntryDataType): string | null {
        if (!data.labels) {
            return null;
        }
        const foundEntries = data.labels.filter(e => e.labelName === FNCH_COMPETITION_ID);
        if (foundEntries.length === 0) {
            return null;
        }
        return foundEntries[0].labelValue;
    }
}
