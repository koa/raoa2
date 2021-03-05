import {Component, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {MediaResolverService} from '../service/media-resolver.service';
import {AlbumListService, QueryAlbumEntry} from '../service/album-list.service';
import {Location} from '@angular/common';
import {IonSlides, LoadingController, MenuController} from '@ionic/angular';
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
    public previousMetadata: AlbumEntryMetadata;
    public nextMetadata: AlbumEntryMetadata;
    public boolean;
    showMetadata = false;

    constructor(private activatedRoute: ActivatedRoute,
                private mediaResolver: MediaResolverService,
                private albumListService: AlbumListService,
                private ngZone: NgZone,
                private location: Location,
                private http: HttpClient,
                private serverApi: ServerApiService,
                private albumEntryDetailGQL: AlbumEntryDetailGQL,
                private menu: MenuController,
                private loadingController: LoadingController
    ) {
        let hackNavi: any;
        hackNavi = window.navigator;
        this.supportShare = hackNavi.share !== undefined;

    }


    public async ngOnInit(): Promise<void> {

        this.albumId = this.activatedRoute.snapshot.paramMap.get('id');
        this.mediaId = this.activatedRoute.snapshot.paramMap.get('mediaId');
        await this.showImage(this.mediaId);
    }

    loadImage(mediaId: string): string {
        if (mediaId === undefined) {
            return '/assets/icon/favicon.ico';
        }
        return this.mediaResolver.lookupImage(this.albumId, mediaId, 3200);
    }

    async showImage(mediaId: string) {
        let waitIndicator;
        const waitTimeoutHandler = window.setTimeout(() => {
            this.loadingController.create({
                cssClass: 'transparent-spinner',
                message: 'Daten werden geladen...',
                translucent: true
            }).then(ind => {
                waitIndicator = ind;
                ind.present();
            });
        }, 500);

        const metadataPromise = this.serverApi.query(this.albumEntryDetailGQL, {albumId: this.albumId, entryId: mediaId})
            .then(result => result.albumById.albumEntry);

        this.albumListService.listAlbum(this.albumId).then(albumData => {
            let lastAlbumEntry: QueryAlbumEntry;
            let previousMediaId: string;
            let nextMediaId: string;
            for (const entry of albumData.sortedEntries) {
                if (lastAlbumEntry !== undefined) {
                    if (entry.id === mediaId) {
                        previousMediaId = lastAlbumEntry.id;
                    } else if (lastAlbumEntry.id === mediaId) {
                        nextMediaId = entry.id;
                    }
                }
                lastAlbumEntry = entry;
            }

            const previousMetadataPromise: Promise<AlbumEntryMetadata> = previousMediaId !== undefined ?
                this.serverApi.query(this.albumEntryDetailGQL, {
                    albumId: this.albumId,
                    entryId: previousMediaId
                }).then(result => result.albumById.albumEntry)
                : Promise.resolve(undefined)
            ;

            const nextMetadataPromise: Promise<AlbumEntryMetadata> = nextMediaId !== undefined ?
                this.serverApi.query(this.albumEntryDetailGQL, {
                    albumId: this.albumId,
                    entryId: nextMediaId
                })
                    .then(result => result.albumById.albumEntry)
                : Promise.resolve(undefined)
            ;
            metadataPromise.then(metadata => {
                this.mediaId = mediaId;
                this.metadata = metadata;
                this.location.replaceState(this.mediaPath(mediaId));

                this.previousMediaId = previousMediaId;

                this.nextMediaId = nextMediaId;

                this.imageSlider.lockSwipeToNext(false);
                this.imageSlider.lockSwipeToPrev(false);
                this.imageSlider.slideTo(1, 0, false);
                this.imageSlider.lockSwipeToNext(nextMediaId === undefined);
                this.imageSlider.lockSwipeToPrev(previousMediaId === undefined);
                window.clearTimeout(waitTimeoutHandler);
                if (waitIndicator !== undefined) {
                    return waitIndicator.dismiss();
                }
                return Promise.resolve();
            });
            Promise.all([previousMetadataPromise, nextMetadataPromise]).then(([prevMetadata, nextMetadata]) => {
                this.previousMetadata = prevMetadata;
                this.nextMetadata = nextMetadata;
            });
        });
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


    async downloadCurrentFile(entryId: string, metadata: AlbumEntryMetadata) {
        const imageBlob = await this.loadOriginal(entryId);
        const a = document.createElement('a');
        const objectUrl = URL.createObjectURL(imageBlob);
        a.href = objectUrl;
        a.download = metadata.name || 'original.jpg';
        a.click();
        a.remove();
        URL.revokeObjectURL(objectUrl);
    }

    private async loadOriginal(entryId: string): Promise<Blob> {
        const loadingOriginalIndicator = await this.loadingController.create({message: 'Original wird geladen...'});
        const src = this.mediaResolver.lookupOriginal(this.albumId, entryId);
        await loadingOriginalIndicator.present();
        return await this.http.get(src, {responseType: 'blob'}).toPromise().finally(() => {
            loadingOriginalIndicator.dismiss();
        });
    }

    async shareCurrentFile(entryId: string, metadata: AlbumEntryMetadata) {
        const contentType = metadata.contentType || 'image/jpeg';
        const filename = metadata.name || 'original.jpg';
        const imageBlob = await this.loadOriginal(entryId);
        console.log('image loaded');
        const lastModified = Date.parse(metadata.created);
        const file = new File([imageBlob], filename, {type: contentType, lastModified});
        const data = {
            title: filename,
            files: [file],
            url: window.location.origin + this.location.prepareExternalUrl(this.mediaPath(this.mediaId))
        };
        console.log('Data: ');
        console.log(data);
        await navigator.share(data).catch(error => {
            console.log('Share error');
            console.log(error.class);
            console.log(error);
        });
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

    openMenu() {
        this.menu.open();
    }

    toggleMetadata() {
        this.showMetadata = !this.showMetadata;
    }
}
