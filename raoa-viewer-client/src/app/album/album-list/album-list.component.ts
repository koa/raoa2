import {Component, NgZone, OnInit} from '@angular/core';
import {CommonServerApiService} from '../../service/common-server-api.service';
import {Location} from '@angular/common';
import {ServerApiService} from '../../service/server-api.service';
import {Title} from '@angular/platform-browser';
import {ActivatedRoute} from '@angular/router';
import {DataService} from '../../service/data.service';
import {AlbumData, AlbumSettings} from '../../service/storage.service';

type MenuEntry = {
    sync: 'none' | 'ready' | 'loading';
    url: string, data: AlbumData
};

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
                private serverApi: ServerApiService,
                private ngZone: NgZone,
                private location: Location,
                private titleService: Title,
                private activatedRoute: ActivatedRoute,
                private albumDataService: DataService) {
    }

    async ngOnInit() {
        this.activatedRoute.paramMap.subscribe(params => {
            this.titleService.setTitle('Liste der Alben');
        });
        const permissions = await this.albumDataService.userPermission();
        this.canManageUsers = permissions.canManageUsers;
        await this.updatePhotoCollectionList();
    }

    async updateSearch(event: CustomEvent) {
        this.photoCollectionFilter = event.detail.value;
        await this.updatePhotoCollectionList();
    }

    private async updatePhotoCollectionList() {
        const entries = (await this.commonServerApi.listCollections(navigator.onLine, this.photoCollectionFilter))
            .map((entry: [AlbumData, AlbumSettings | undefined]) => {
                const newVar: MenuEntry = {
                    sync: entry[1]?.syncOffline ? (entry[0].albumVersion === entry[1]?.offlineSyncedVersion ? 'ready' : 'loading') : 'none',
                    url: '/album/' + entry[0].id,
                    data: entry[0]
                };
                return newVar;
            });
        this.ngZone.run(() => this.foundAlbums = entries);
    }

    back() {
        this.location.back();
    }


    public async enableSync(id: string) {
        await this.albumDataService.setSync(id, true);
        await this.updatePhotoCollectionList();
    }

    public async disableSync(id: string) {
        await this.albumDataService.setSync(id, false);
        await this.updatePhotoCollectionList();
    }
}
