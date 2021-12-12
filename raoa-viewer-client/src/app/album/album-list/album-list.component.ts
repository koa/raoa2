import {Component, NgZone, OnDestroy, OnInit} from '@angular/core';
import {CommonServerApiService} from '../../service/common-server-api.service';
import {Location} from '@angular/common';
import {ServerApiService} from '../../service/server-api.service';
import {Title} from '@angular/platform-browser';
import {ActivatedRoute} from '@angular/router';
import {DataService} from '../../service/data.service';
import {AlbumData, AlbumSettings} from '../../service/storage.service';
import {MenuController} from '@ionic/angular';
import {bufferTime, filter, tap} from 'rxjs/operators';
import {merge, Subscription} from 'rxjs';

type MenuEntry = {
    sync: 'none' | 'ready' | 'loading';
    url: string, data: AlbumData
};

@Component({
    selector: 'app-album-list',
    templateUrl: './album-list.component.html',
    styleUrls: ['./album-list.component.css'],
})
export class AlbumListComponent implements OnInit, OnDestroy {
    private photoCollectionFilter: string;
    public foundAlbums: MenuEntry[];
    public canManageUsers = false;
    private subscription: Subscription;

    constructor(private commonServerApi: CommonServerApiService,
                private serverApi: ServerApiService,
                private ngZone: NgZone,
                private location: Location,
                private titleService: Title,
                private activatedRoute: ActivatedRoute,
                private albumDataService: DataService,
                private menuController: MenuController
    ) {
    }

    async ngOnInit() {
        this.activatedRoute.paramMap.subscribe(params => {
            this.titleService.setTitle('Liste der Alben');
        });
        const permissions = await this.albumDataService.userPermission();
        this.canManageUsers = permissions.canManageUsers;
        await this.updatePhotoCollectionList();

        this.subscription = merge(this.albumDataService.albumModified, this.albumDataService.onlineState)
            .pipe(
                bufferTime(1000),
                filter(a => a.length > 0))
            .subscribe(albums => {
                this.updatePhotoCollectionList();
            });
    }

    ngOnDestroy() {
        this.subscription?.unsubscribe();
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

    public openNavigationMenu(): Promise<void> {
        return this.menuController.open('navigation').then();
    }
}
