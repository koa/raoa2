import {Component, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {MediaResolverService} from '../service/media-resolver.service';
import {AlbumListService, QueryAlbumEntry} from '../service/album-list.service';
import {Location} from '@angular/common';
import {IonSlides} from '@ionic/angular';

@Component({
    selector: 'app-show-single-media',
    templateUrl: './show-single-media.component.html',
    styleUrls: ['./show-single-media.component.css'],
})
export class ShowSingleMediaComponent implements OnInit {
    public albumId: string;
    public mediaId: string;
    public previousMediaId: string;
    public nextMediaId: string;
    private alreadySlidedNext = false;
    @ViewChild('imageSlider', {static: true})
    private imageSlider: IonSlides;

    public slideOpts = {
        initialSlide: 1
    };

    constructor(private activatedRoute: ActivatedRoute,
                private mediaResolver: MediaResolverService,
                private albumListService: AlbumListService,
                private ngZone: NgZone,
                private location: Location) {
    }

    imageSliderReady(imageSlider: IonSlides) {
        setTimeout(() => {
            console.log('Image slider: ' + imageSlider);
            this.imageSlider = imageSlider;
        }, 0);
    }

    ngOnInit() {
        this.albumId = this.activatedRoute.snapshot.paramMap.get('id');
        this.mediaId = this.activatedRoute.snapshot.paramMap.get('mediaId');
        this.showImage(this.mediaId);
    }

    private updateMetadata() {
        this.albumListService.listAlbum(this.albumId).then(albumData => {
            let lastAlbumEntry: QueryAlbumEntry;
            let previousMediaId: string;
            let nextMediaId: string;
            for (const entry of albumData.sortedEntries) {
                if (lastAlbumEntry !== undefined) {
                    if (entry.id === this.mediaId) {
                        previousMediaId = lastAlbumEntry.id;
                    } else if (lastAlbumEntry.id === this.mediaId) {
                        nextMediaId = entry.id;
                    }
                }
                lastAlbumEntry = entry;
            }
            this.ngZone.run(() => {
                this.previousMediaId = previousMediaId;
                this.nextMediaId = nextMediaId;
                this.imageSlider.lockSwipeToNext(nextMediaId === undefined);
                this.imageSlider.lockSwipeToPrev(previousMediaId === undefined);
            });
        });
    }

    loadImage(mediaId: string): string {
        return this.mediaResolver.lookupImage(this.albumId, mediaId, 3200);
    }

    showImage(mediaId: string) {
        this.alreadySlidedNext = false;
        this.mediaId = mediaId;
        this.nextMediaId = undefined;
        this.previousMediaId = undefined;
        this.location.replaceState('/album/' + this.albumId + '/media/' + mediaId);
        this.imageSlider.lockSwipeToNext(false);
        this.imageSlider.lockSwipeToPrev(false);
        this.imageSlider.slideTo(1, 0, false);
        this.imageSlider.lockSwipeToNext(true);
        this.imageSlider.lockSwipeToPrev(true);
        this.updateMetadata();
    }


    slided() {
        this.imageSlider.getActiveIndex().then(index => {
            if (index === 2 && this.nextMediaId !== undefined) {
                this.showImage(this.nextMediaId);
            } else if (index === 0 && this.previousMediaId !== undefined) {
                this.showImage(this.previousMediaId);
            }
        });
    }

}
