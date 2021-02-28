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
    private loadingElement: HTMLIonLoadingElement;
    public foundAlbums: MenuEntry[];

    constructor(private commonServerApi: CommonServerApiService,
                private ngZone: NgZone,
                private location: Location,
                private loadingController: LoadingController) {
    }

    async ngOnInit() {
        this.loadingElement = await this.loadingController.create();
        await this.updatePhotoCollectionList();
    }

    updateSearch(event: CustomEvent) {
        this.photoCollectionFilter = event.detail.value;
        this.updatePhotoCollectionList();
    }

    private async updatePhotoCollectionList() {
        await this.loadingElement.present();
        const entries = await this.commonServerApi.listCollections(this.photoCollectionFilter);
        this.ngZone.run(() => {
            this.foundAlbums = entries;
            this.loadingElement.remove();
        });
    }

    back() {
        this.location.back();
    }
}
