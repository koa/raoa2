import {Component, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {MediaResolverService} from '../service/media-resolver.service';

@Component({
    selector: 'app-show-single-media',
    templateUrl: './show-single-media.component.html',
    styleUrls: ['./show-single-media.component.css'],
})
export class ShowSingleMediaComponent implements OnInit {
    private albumId: string;
    private mediaId: string;


    constructor(private activatedRoute: ActivatedRoute, private mediaResolver: MediaResolverService) {
    }

    ngOnInit() {
        this.albumId = this.activatedRoute.snapshot.paramMap.get('id');
        this.mediaId = this.activatedRoute.snapshot.paramMap.get('mediaId');
        console.log('Album: ' + this.albumId + ', media: ' + this.mediaId);

    }

    loadImage(): string {
        const entryId = this.mediaId;
        return this.mediaResolver.lookupImage(this.albumId, entryId, 3200);
    }
}
