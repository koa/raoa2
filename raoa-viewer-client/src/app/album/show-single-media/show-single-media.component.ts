import {Component, ElementRef, HostListener, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {MediaResolverService} from '../service/media-resolver.service';
import {Location} from '@angular/common';
import {IonInput, IonSlides, LoadingController} from '@ionic/angular';
import {HttpClient} from '@angular/common/http';
import {AlbumEntry} from '../../generated/graphql';
import {ServerApiService} from '../../service/server-api.service';
import {Title} from '@angular/platform-browser';
import {DataService} from '../../service/data.service';
import {AlbumEntryData} from '../../service/storage.service';

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

    constructor(private activatedRoute: ActivatedRoute,
                private mediaResolver: MediaResolverService,
                private dataService: DataService,
                private ngZone: NgZone,
                private location: Location,
                private http: HttpClient,
                private serverApi: ServerApiService,
                private loadingController: LoadingController,
                private titleService: Title
    ) {
        let hackNavi: any;
        hackNavi = window.navigator;
        this.supportShare = hackNavi.share !== undefined;

    }

    public albumId: string;
    public mediaId: string;
    public previousMediaId: string;
    public nextMediaId: string;
    public currentMediaContent: Promise<string> = undefined;
    public previousMediaContent: Promise<string> = undefined;
    public nextMediaContent: Promise<string> = undefined;
    @ViewChild('imageSlider', {static: true})
    private imageSlider: IonSlides;
    @ViewChild('videoRoot') private element: ElementRef<HTMLDivElement>;
    public supportShare: boolean;
    public metadata: AlbumEntryData;
    public showDetails: 'Metadata' | 'Keyword' | null = null;
    public albumKeywords: string[] = [];
    public allKnownAlbumKeywords = new Set<string>();
    public currentSelectedKeywords = new Set<string>();
    public canEdit = false;
    public currentIsVideo = false;
    private filteringKeyword: string;
    private nextIdMap: Map<BigInt, BigInt> = new Map<BigInt, BigInt>();
    private prevIdMap: Map<BigInt, BigInt> = new Map<BigInt, BigInt>();
    public elementWidth = 3200;
    public playVideo = false;

    private static bigint2objectId(value: BigInt): string {
        if (value === undefined) {
            return undefined;
        }
        return value.toString(16).padStart(40, '0');
    }


    public async resized() {
        this.elementWidth = this.element.nativeElement.clientWidth;
    }

    public async ngOnInit(): Promise<void> {
        const snapshot = this.activatedRoute.snapshot;
        const paramMap = snapshot.paramMap;
        const queryParam = snapshot.queryParamMap;
        this.albumId = paramMap.get('id');
        const mediaId = paramMap.get('mediaId');
        this.filteringKeyword = queryParam.get('keyword') || undefined;
        const permissions = await this.dataService.userPermission();

        await this.showImage(mediaId);
        await this.refreshSequence();
        const mediaIdAsInt = BigInt('0x' + mediaId);
        const previousMediaId = ShowSingleMediaComponent.bigint2objectId(this.prevIdMap.get(mediaIdAsInt));
        const nextMediaId = ShowSingleMediaComponent.bigint2objectId(this.nextIdMap.get(mediaIdAsInt));
        this.previousMediaContent = previousMediaId
            ? this.dataService.getImage(this.albumId, previousMediaId, 3200)
            : undefined;
        this.nextMediaContent = nextMediaId
            ? this.dataService.getImage(this.albumId, nextMediaId, 3200)
            : undefined;
        this.ngZone.run(() => {
            this.nextMediaId = nextMediaId;
            this.previousMediaId = previousMediaId;
            this.canEdit = permissions.canEdit;
        });
        await this.refreshControls();
    }


    loadVideo(mediaId: string): string {
        if (mediaId === undefined) {
            return undefined;
        }
        return this.mediaResolver.lookupVideo(this.albumId, mediaId, this.elementWidth);
    }


    isVideo(mediaId: string): boolean {
        if (mediaId === undefined) {
            return false;
        }
    }

    async refreshSequence() {
        this.prevIdMap.clear();
        this.nextIdMap.clear();
        this.allKnownAlbumKeywords.clear();
        const [albumData, albumEntries] = await this.dataService.listAlbum(this.albumId);
        let lastAlbumId: BigInt;
        const sortedEntries = this.filteringKeyword === undefined ?
            albumEntries :
            albumEntries.filter(entry => entry.keywords.findIndex(k => k === this.filteringKeyword) >= 0);
        for (const entry of sortedEntries) {
            const myId: BigInt = BigInt('0x' + entry.albumEntryId);
            if (lastAlbumId !== undefined) {
                this.nextIdMap.set(lastAlbumId, myId);
                this.prevIdMap.set(myId, lastAlbumId);
            }
            entry.keywords.forEach(kw => this.allKnownAlbumKeywords.add(kw));
            lastAlbumId = myId;
        }
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
            const mediaIdAsInt = BigInt('0x' + mediaId);
            const previousMediaId = ShowSingleMediaComponent.bigint2objectId(this.prevIdMap.get(mediaIdAsInt));
            const nextMediaId = ShowSingleMediaComponent.bigint2objectId(this.nextIdMap.get(mediaIdAsInt));
            const [albumEntry, keywords] = await this.dataService.getAlbumEntry(this.albumId, mediaId);

            this.ngZone.run(() => {
                if (mediaId === this.nextMediaId) {
                    // move fast forward
                    this.previousMediaContent = this.currentMediaContent;
                    this.currentMediaContent = this.nextMediaContent;
                    this.nextMediaContent = nextMediaId
                        ? this.dataService.getImage(this.albumId, nextMediaId, 3200)
                        : undefined;
                } else if (mediaId === this.previousMediaId) {
                    // move fast backward
                    this.nextMediaContent = this.currentMediaContent;
                    this.currentMediaContent = this.previousMediaContent;
                    this.previousMediaContent = previousMediaId
                        ? this.dataService.getImage(this.albumId, previousMediaId, 3200)
                        : undefined;
                } else if (this.mediaId !== mediaId) {
                    // move anywhere else
                    this.currentMediaContent = this.dataService.getImage(this.albumId, mediaId, 3200);
                    this.previousMediaContent = previousMediaId
                        ? this.dataService.getImage(this.albumId, previousMediaId, 3200)
                        : undefined;
                    this.nextMediaContent = nextMediaId
                        ? this.dataService.getImage(this.albumId, nextMediaId, 3200)
                        : undefined;
                }
                this.mediaId = mediaId;
                this.metadata = albumEntry;
                this.titleService.setTitle(albumEntry.name);
                this.location.replaceState(this.mediaPath(mediaId));
                this.previousMediaId = previousMediaId;
                this.nextMediaId = nextMediaId;
                this.currentSelectedKeywords = new Set(keywords);
                const allKeywords = new Set(this.allKnownAlbumKeywords);
                this.currentSelectedKeywords.forEach(keyword => allKeywords.add(keyword));
                this.currentIsVideo = albumEntry.entryType === 'video';
                this.playVideo = false;
                this.albumKeywords = [];
                allKeywords.forEach(keyword => this.albumKeywords.push(keyword));
                this.albumKeywords.sort((k1, k2) => k1.localeCompare(k2));
            });
            await this.refreshControls();
        } finally {
            window.clearTimeout(waitTimeoutHandler);
            if (waitIndicator !== undefined) {
                waitIndicator.dismiss();
            }
        }

    }


    private async refreshControls() {
        await this.imageSlider.lockSwipeToNext(false);
        await this.imageSlider.lockSwipeToPrev(false);
        await this.imageSlider.slideTo(1, 0, false);
        await this.imageSlider.lockSwipeToNext(this.nextMediaId === undefined);
        await this.imageSlider.lockSwipeToPrev(this.previousMediaId === undefined);
    }

    private mediaPath(mediaId: string) {
        if (this.filteringKeyword === undefined) {
            return '/album/' + this.albumId + '/media/' + mediaId;
        } else {
            return '/album/' + this.albumId + '/media/' + mediaId + '?keyword=' + this.filteringKeyword;
        }
    }

    async slided() {
        const index = await this.imageSlider.getActiveIndex();
        if (index === 2 && this.nextMediaId !== undefined) {
            await this.showImage(this.nextMediaId);
        } else if (index === 0 && this.previousMediaId !== undefined) {
            await this.showImage(this.previousMediaId);
        }
    }


    async downloadCurrentFile(entryId: string, metadata: AlbumEntryData) {
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

    async shareCurrentFile(entryId: string, metadata: AlbumEntryData) {
        const contentType = metadata.contentType || 'image/jpeg';
        const filename = metadata.name || 'original.jpg';
        const imageBlob = await this.loadOriginal(entryId);
        // console.log('image loaded');
        const lastModified = metadata.created;
        const file = new File([imageBlob], filename, {type: contentType, lastModified});
        const data = {
            title: filename,
            files: [file],
            url: window.location.origin + this.location.prepareExternalUrl(this.mediaPath(this.mediaId))
        };
        // console.log('Data: ');
        // console.log(data);
        await navigator.share(data).catch(error => {
            console.log('Share error');
            console.log(error.class);
            console.log(error);
        });
    }

    async back() {
        this.location.back();
    }

    calcTime(exposureTime: number): string {
        if (exposureTime < 1) {
            return '1/' + (1 / exposureTime).toFixed(0);
        } else {
            return exposureTime.toFixed(0);
        }
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
        } else if ($event.key === 'ArrowLeft' && this.previousMediaId) {
            await this.showImage(this.previousMediaId);
        } else if ($event.key === 'Escape') {
            await this.back();
        }
    }

    async addKeyword($event: KeyboardEvent) {
        const input: IonInput = $event.target as unknown as IonInput;
        if (typeof input.value === 'string') {
            const newKeyword: string = input.value;
            this.allKnownAlbumKeywords.add(newKeyword);
            input.value = null;
            await this.dataService.addKeyword(this.albumId, [this.mediaId], [newKeyword]);
            await this.showImage(this.mediaId);
        }

    }

    async toggleKeyword(keyword: string) {
        if (this.currentSelectedKeywords.has(keyword)) {
            await this.dataService.removeKeyword(this.albumId, [this.mediaId], [keyword]);
        } else {
            await this.dataService.addKeyword(this.albumId, [this.mediaId], [keyword]);
        }
        await this.showImage(this.mediaId);
    }

}
