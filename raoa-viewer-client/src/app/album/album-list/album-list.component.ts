import {Component, NgZone, OnInit} from '@angular/core';
import {CommonServerApiService, MenuEntry} from '../../service/common-server-api.service';
import {Location} from '@angular/common';
import {LoadingController} from '@ionic/angular';

@Component({
    selector: 'app-album-list',
    templateUrl: './album-list.component.html',
    styleUrls: ['./album-list.component.css'],
})
export class AlbumListComponent implements OnInit {
    private photoCollectionFilter: string;
    public foundAlbums: MenuEntry[];

    constructor(private commonServerApi: CommonServerApiService,
                private ngZone: NgZone,
                private location: Location,
                private loadingController: LoadingController) {
    }

    async ngOnInit() {
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
}
