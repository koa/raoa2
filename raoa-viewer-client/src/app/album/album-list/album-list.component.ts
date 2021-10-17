import {Component, NgZone, OnInit} from '@angular/core';
import {AlbumEntryDataType, CommonServerApiService, MenuEntry} from '../../service/common-server-api.service';
import {Location} from '@angular/common';
import {FNCH_COMPETITION_ID} from '../../constants';
import {ServerApiService} from '../../service/server-api.service';
import {Title} from '@angular/platform-browser';
import {ActivatedRoute} from '@angular/router';
import {DataService} from '../../service/data.service';

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
        const entries = await this.commonServerApi.listCollections(this.photoCollectionFilter);
        this.ngZone.run(() => this.foundAlbums = entries);
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
