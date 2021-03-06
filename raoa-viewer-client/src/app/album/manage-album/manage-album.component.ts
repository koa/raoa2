import {Component, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {ServerApiService} from '../../service/server-api.service';
import {QueryAlbumSettingsGQL} from '../../generated/graphql';

@Component({
    selector: 'app-manage-album',
    templateUrl: './manage-album.component.html',
    styleUrls: ['./manage-album.component.css'],
})
export class ManageAlbumComponent implements OnInit {
    public albumId: string;

    constructor(private activatedRoute: ActivatedRoute,
                private serverApi: ServerApiService,
                private queryAlbumSettingsGQL: QueryAlbumSettingsGQL
    ) {
    }

    ngOnInit() {
        this.albumId = this.activatedRoute.snapshot.paramMap.get('id');
        this.serverApi.query(this.queryAlbumSettingsGQL, {albumId: this.albumId});
    }

}
