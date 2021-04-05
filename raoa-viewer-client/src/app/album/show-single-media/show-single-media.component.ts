import {Component, HostListener, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {MediaResolverService} from '../service/media-resolver.service';
import {AlbumListService, QueryAlbumEntry} from '../service/album-list.service';
import {Location} from '@angular/common';
import {IonInput, IonSlides, LoadingController, MenuController} from '@ionic/angular';
import {HttpClient} from '@angular/common/http';
import {AlbumEntry, AlbumEntryDetailGQL, ShowSingleMediaEditKeywordsGQL} from '../../generated/graphql';
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
    public showDetails: 'Metadata' | 'Keyword' | null = null;
    public albumKeywords: string[] = [];
    public currentSelectedKeywords = new Set<string>();
    public canEdit = false;
    private filteringKeyword: string;

    constructor(private activatedRoute: ActivatedRoute,
                private mediaResolver: MediaResolverService,
                private albumListService: AlbumListService,
                private ngZone: NgZone,
                private location: Location,
                private http: HttpClient,
                private serverApi: ServerApiService,
                private albumEntryDetailGQL: AlbumEntryDetailGQL,
                private showSingleMediaEditKeywordsGQL: ShowSingleMediaEditKeywordsGQL,
                private menu: MenuController,
                private loadingController: LoadingController
    ) {
        let hackNavi: any;
        hackNavi = window.navigator;
        this.supportShare = hackNavi.share !== undefined;

    }


    public async ngOnInit(): Promise<void> {
        const snapshot = this.activatedRoute.snapshot;
        const paramMap = snapshot.paramMap;
        const queryParam = snapshot.queryParamMap;
        this.albumId = paramMap.get('id');
        this.mediaId = paramMap.get('mediaId');
        this.filteringKeyword = queryParam.get('keyword') || undefined;
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
        try {
            await this.storeKeywordMutation();
            const albumData = await this.albumListService.listAlbum(this.albumId);
            let lastAlbumEntry: QueryAlbumEntry;
            let previousMediaId: string;
            let nextMediaId: string;
            const sortedEntries = this.filteringKeyword === undefined ?
                albumData.sortedEntries :
                albumData.sortedEntries.filter(entry => entry.keywords.findIndex(k => k === this.filteringKeyword) >= 0);
            for (const entry of sortedEntries) {
                if (lastAlbumEntry !== undefined) {
                    if (entry.id === mediaId) {
                        previousMediaId = lastAlbumEntry.id;
                    } else if (lastAlbumEntry.id === mediaId) {
                        nextMediaId = entry.id;
                    }
                }
                lastAlbumEntry = entry;
            }
            const metadata = await this.serverApi.query(this.albumEntryDetailGQL, {albumId: this.albumId, entryId: mediaId});

            this.mediaId = mediaId;
            this.metadata = metadata.albumById.albumEntry;
            this.canEdit = metadata.currentUser.canEdit;
            this.location.replaceState(this.mediaPath(mediaId));
            this.previousMediaId = previousMediaId;
            this.nextMediaId = nextMediaId;
            this.currentSelectedKeywords = new Set(metadata.albumById.albumEntry.keywords);
            const allKeywords = new Set(this.albumKeywords);
            this.currentSelectedKeywords.forEach(keyword => allKeywords.add(keyword));
            for (const keyword of albumData.keywords.keys()) {
                allKeywords.add(keyword);
            }
            this.albumKeywords = [];
            allKeywords.forEach(keyword => this.albumKeywords.push(keyword));
            this.albumKeywords.sort((k1, k2) => k1.localeCompare(k2));

            await this.imageSlider.lockSwipeToNext(false);
            await this.imageSlider.lockSwipeToPrev(false);
            await this.imageSlider.slideTo(1, 0, false);
            await this.imageSlider.lockSwipeToNext(nextMediaId === undefined);
            await this.imageSlider.lockSwipeToPrev(previousMediaId === undefined);
            const previousMetadataPromise: Promise<AlbumEntryMetadata> = previousMediaId !== undefined ?
                this.serverApi.query(this.albumEntryDetailGQL, {
                    albumId: this.albumId,
                    entryId: previousMediaId
                }).then(result => result.albumById.albumEntry)
                : Promise.resolve(undefined);

            const nextMetadataPromise: Promise<AlbumEntryMetadata> = nextMediaId !== undefined ?
                this.serverApi.query(this.albumEntryDetailGQL, {
                    albumId: this.albumId,
                    entryId: nextMediaId
                })
                    .then(result => result.albumById.albumEntry)
                : Promise.resolve(undefined);

            Promise.all([previousMetadataPromise, nextMetadataPromise]).then(([prevMetadata, nextMetadata]) => {
                this.previousMetadata = prevMetadata;
                this.nextMetadata = nextMetadata;
            });
        } finally {
            window.clearTimeout(waitTimeoutHandler);
            if (waitIndicator !== undefined) {
                waitIndicator.dismiss();
            }
        }

    }


    private async storeKeywordMutation() {
        if (this.metadata && this.mediaId) {
            const oldKeywords = new Set(this.metadata.keywords);
            const removeKeywords: string[] = [];
            const addKeywords: string[] = [];
            oldKeywords.forEach(kw => {
                if (!this.currentSelectedKeywords.has(kw)) {
                    removeKeywords.push(kw);
                }
            });
            this.currentSelectedKeywords.forEach(kw => {
                if (!oldKeywords.has(kw)) {
                    addKeywords.push(kw);
                }
            });
            if (removeKeywords.length > 0 || addKeywords.length > 0) {
                await this.serverApi.update(this.showSingleMediaEditKeywordsGQL, {
                    albumId: this.albumId, albumEntryId: this.mediaId, mutation: {
                        addKeywords,
                        removeKeywords
                    }
                });
                await this.serverApi.clear();
            }
        }
    }

    private mediaPath(mediaId: string) {
        if (this.filteringKeyword === undefined) {
            return '/album/' + this.albumId + '/media/' + mediaId;
        } else {
            return '/album/' + this.albumId + '/media/' + mediaId + '?keyword=' + this.filteringKeyword;
        }
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

    async back() {
        await this.storeKeywordMutation();
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
        if (this.showDetails === 'Metadata') {
            this.showDetails = null;
        } else {
            this.showDetails = 'Metadata';
        }
    }

    toggleKeywords() {
        if (this.showDetails === 'Keyword') {
            this.showDetails = null;
        } else {
            this.showDetails = 'Keyword';
        }

    }

    @HostListener('window:keyup', ['$event'])
    async keyup($event: KeyboardEvent) {
        if ($event.key === 'ArrowRight' && this.nextMediaId) {
            await this.showImage(this.nextMediaId);
        }
        if ($event.key === 'ArrowLeft' && this.previousMediaId) {
            await this.showImage(this.previousMediaId);
        }
    }

    addKeyword($event: KeyboardEvent) {
        const input: IonInput = $event.target as unknown as IonInput;
        if (typeof input.value === 'string') {
            const newKeyword: string = input.value;
            if (this.albumKeywords.filter(k => k === newKeyword).length === 0) {
                this.albumKeywords.push(newKeyword);
            }
            this.currentSelectedKeywords.add(newKeyword);
            input.value = null;
        }

    }

    toggleKeyword(keyword: string) {
        if (this.currentSelectedKeywords.has(keyword)) {
            this.currentSelectedKeywords.delete(keyword);
        } else {
            this.currentSelectedKeywords.add(keyword);
        }
    }
}
