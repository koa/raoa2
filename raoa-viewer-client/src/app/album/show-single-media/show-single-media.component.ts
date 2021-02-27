import {Component, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {MediaResolverService} from '../service/media-resolver.service';
import {AlbumListService, QueryAlbumEntry} from '../service/album-list.service';
import {Location} from '@angular/common';
import {IonSlides} from '@ionic/angular';
import {HttpClient} from '@angular/common/http';
import {AlbumEntry, AlbumEntryDetailGQL} from '../../generated/graphql';
import {ServerApiService} from '../../service/server-api.service';

type AlbumEntryMetadata =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'name' |
    'created' | 'cameraModel' | 'exposureTime' | 'fNumber' | 'focalLength35' | 'isoSpeedRatings' | 'keywords' | 'contentType'>;

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
    @ViewChild('imageSlider', {static: true})
    private imageSlider: IonSlides;
    public supportShare: boolean;
    public metadata: AlbumEntryMetadata;

    constructor(private activatedRoute: ActivatedRoute,
                private mediaResolver: MediaResolverService,
                private albumListService: AlbumListService,
                private ngZone: NgZone,
                private location: Location,
                private http: HttpClient,
                private serverApi: ServerApiService,
                private albumEntryDetailGQL: AlbumEntryDetailGQL) {
        let hackNavi: any;
        hackNavi = window.navigator;
        this.supportShare = hackNavi.share !== undefined;

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
        this.serverApi.query(this.albumEntryDetailGQL, {albumId: this.albumId, entryId: this.mediaId})
            .then(result => this.ngZone.run(() => this.metadata = result.albumById.albumEntry))
        ;
    }

    loadImage(mediaId: string): string {
        return this.mediaResolver.lookupImage(this.albumId, mediaId, 3200);
    }

    showImage(mediaId: string) {
        this.mediaId = mediaId;
        this.nextMediaId = undefined;
        this.previousMediaId = undefined;
        this.location.replaceState(this.mediaPath(mediaId));
        this.imageSlider.lockSwipeToNext(false);
        this.imageSlider.lockSwipeToPrev(false);
        this.imageSlider.slideTo(1, 0, false);
        this.imageSlider.lockSwipeToNext(true);
        this.imageSlider.lockSwipeToPrev(true);
        this.updateMetadata();
    }


    private mediaPath(mediaId: string) {
        return '/album/' + this.albumId + '/media/' + mediaId;
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


    async downloadCurrentFile() {
        const imageBlob = await this.loadOriginal();
        const a = document.createElement('a');
        const objectUrl = URL.createObjectURL(imageBlob);
        a.href = objectUrl;
        const filename = this.metadata.name || 'original.jpg';
        a.download = filename;
        a.click();
        a.remove();
        URL.revokeObjectURL(objectUrl);
    }

    private async loadOriginal(): Promise<Blob> {
        const src = this.mediaResolver.lookupOriginal(this.albumId, this.mediaId);
        console.log('src: ' + src);
        return await this.http.get(src, {responseType: 'blob'}).toPromise();
    }

    async shareCurrentFile() {
        const contentType = this.metadata.contentType || 'image/jpeg';
        const filename = this.metadata.name || 'original.jpg';
        const imageBlob = await this.loadOriginal();
        const lastModified = Date.parse(this.metadata.created);
        const file = new File([imageBlob], filename, {type: contentType, lastModified});
        const data = {
            title: filename,
            files: [file],
            url: window.location.origin + this.location.prepareExternalUrl(this.mediaPath(this.mediaId))
        };
        console.log('Data: ');
        console.log(data);
        await navigator.share(data);
    }

    back() {
        this.location.back();
    }

    calcTime(exposureTime: number): string {
        if (exposureTime < 1) {
            return '1/' + (1 / exposureTime).toFixed(0);
        } else {
            return exposureTime.toFixed(0);
        }
    }
}
