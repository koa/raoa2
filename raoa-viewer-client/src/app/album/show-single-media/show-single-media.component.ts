import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {MediaResolverService} from '../service/media-resolver.service';
import {AlbumListService, QueryAlbumEntry} from '../service/album-list.service';
import {Location} from '@angular/common';

@Component({
    selector: 'app-show-single-media',
    templateUrl: './show-single-media.component.html',
    styleUrls: ['./show-single-media.component.css'],
})
export class ShowSingleMediaComponent implements OnInit {
    public albumId: string;
    private mediaId: string;
    public previousMediaId: string;
    public nextMediaId: string;

    constructor(private activatedRoute: ActivatedRoute,
                private mediaResolver: MediaResolverService,
                private albumListService: AlbumListService,
                private ngZone: NgZone,
                private location: Location) {
    }

    ngOnInit() {
        this.albumId = this.activatedRoute.snapshot.paramMap.get('id');
        this.mediaId = this.activatedRoute.snapshot.paramMap.get('mediaId');
        this.updateMetadata();
    }

    private updateMetadata() {
        this.albumListService.listAlbum(this.albumId).then(albumData => {
            let lastAlbumEntry: QueryAlbumEntry;
            let previousMediaId: string;
            let nextMediaId: string;
            for (const entry of albumData.sortedEntries) {
                if (entry.id === this.mediaId) {
                    previousMediaId = lastAlbumEntry.id;
                } else if (lastAlbumEntry !== undefined && lastAlbumEntry.id === this.mediaId) {
                    nextMediaId = entry.id;
                }
                lastAlbumEntry = entry;
            }
            this.ngZone.run(() => {
                this.previousMediaId = previousMediaId;
                this.nextMediaId = nextMediaId;
            });
        });
    }

    loadImage(): string {
        const entryId = this.mediaId;
        return this.mediaResolver.lookupImage(this.albumId, entryId, 3200);
    }

    showImage(mediaId: string) {
        this.mediaId = mediaId;
        this.nextMediaId = undefined;
        this.previousMediaId = undefined;
        this.location.replaceState('/album/' + this.albumId + '/media/' + mediaId);
        this.updateMetadata();
    }
}
