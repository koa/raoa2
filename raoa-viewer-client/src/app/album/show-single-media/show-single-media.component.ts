import {Component, ElementRef, HostListener, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute, ParamMap} from '@angular/router';
import {MediaResolverService} from '../service/media-resolver.service';
import {Location} from '@angular/common';
import {IonInput, IonSlides, LoadingController} from '@ionic/angular';
import {HttpClient} from '@angular/common/http';
import {ServerApiService} from '../../service/server-api.service';
import {Title} from '@angular/platform-browser';
import {createFilter, DataService, filterTimeResolution} from '../../service/data.service';
import {AlbumEntryData} from '../../service/storage.service';


export type KeywordCombine = 'and' | 'or';
export type TimeRange = [number, number] | undefined;

export interface FilterQueryParams {
    keyword: string | undefined;
    c: string | undefined;
    tf: number | undefined;
    tu: number | undefined;
    tr: number | undefined;
}

export function createFilterQueryParams(
    keywords: string[],
    keywordCombine: KeywordCombine,
    filteringTimeRange: TimeRange,
    timeResolution: number): FilterQueryParams {
    const [keyword, c] = (keywords !== undefined && keywords.length > 0)
        ? [keywords.map(v => encodeURI(v)).join(','), keywordCombine === 'and' ? 'a' : 'o']
        : [undefined, undefined];
    const [tf, tu] = filteringTimeRange !== undefined
        ? filteringTimeRange
        : [undefined, undefined];
    const tr = timeResolution > 0 ? timeResolution : undefined;
    return {
        keyword, c,
        tf, tu,
        tr
    };

}

export function updateSearchParams(
    keywords: string[],
    keywordCombine: KeywordCombine,
    filteringTimeRange: TimeRange | undefined,
    timeResolution: number,
    params: URLSearchParams) {
    if (keywords !== undefined && keywords.length > 0) {
        params.set('keyword', keywords.map(kw => encodeURI(kw)).join(','));
        params.set('c', keywordCombine === 'and' ? 'a' : 'o');
    } else {
        params.delete('keyword');
        params.delete('c');
    }
    if (filteringTimeRange !== undefined && filteringTimeRange !== null) {
        params.set('tf', filteringTimeRange[0].toString(10));
        params.set('tu', filteringTimeRange[1].toString(10));
    } else {
        params.delete('tf');
        params.delete('tu');
    }
    if (timeResolution > 0) {
        params.set('tr', timeResolution.toString(10));
    } else {
        params.delete('tr');
    }
}

export function collectFilterParams(
    keywords: string[],
    keywordCombine: KeywordCombine,
    filteringTimeRange: TimeRange,
    timeResolution: number):
    URLSearchParams {
    const params = new URLSearchParams();
    updateSearchParams(keywords, keywordCombine, filteringTimeRange, timeResolution, params);
    return params;
}


export function createMediaPath(
    albumId: string,
    mediaId: string,
    keywords: string[],
    keywordCombine: KeywordCombine,
    filteringTimeRange: TimeRange,
    timeResolution: number): URL {
    const path = new URL(window.location.href);
    path.pathname = '/album/' + albumId + '/media/' + mediaId;
    updateSearchParams(keywords, keywordCombine, filteringTimeRange, timeResolution, path.searchParams);
    return path;
}


export function parseFilterParams(queryParam: ParamMap): [string[], KeywordCombine, TimeRange, number] {
    const filteringKeyword = queryParam.get('keyword') || undefined;
    const combination = queryParam.get('c');
    const tf = queryParam.get('tf');
    const tu = queryParam.get('tu');
    const tr = queryParam.get('tr');
    return [
        filteringKeyword?.split(',').map(e => decodeURI(e)) ?? [],
        combination ? combination === 'a' ? 'and' : 'or' : 'and',
        tf && tu ? [Number.parseInt(tf, 10), Number.parseInt(tu, 10)] : undefined,
        tr ? Number.parseInt(tr, 10) : 0
    ];
}

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
    private filteringKeywords: string[];
    private keywordCombine: KeywordCombine = 'and';
    private filteringTimeRange: [number, number] | undefined;
    private nextIdMap: Map<BigInt, BigInt> = new Map<BigInt, BigInt>();
    private prevIdMap: Map<BigInt, BigInt> = new Map<BigInt, BigInt>();
    public bigImageSize = 1600;
    public playVideo = false;
    private timeResolution = 0;

    private static bigint2objectId(value: BigInt): string {
        if (value === undefined) {
            return undefined;
        }
        return value.toString(16).padStart(40, '0');
    }


    public async resized() {
        this.bigImageSize = Math.max(window.screen.width, window.screen.height);
    }

    public async ngOnInit(): Promise<void> {
        const snapshot = this.activatedRoute.snapshot;
        const paramMap = snapshot.paramMap;
        const queryParam = snapshot.queryParamMap;
        this.albumId = paramMap.get('id');
        const mediaId = paramMap.get('mediaId');
        const [filteringKeywords, keywordCombine, filteringTimeRange, timeResolution] = parseFilterParams(queryParam);

        this.filteringKeywords = filteringKeywords;
        this.keywordCombine = keywordCombine;
        this.filteringTimeRange = filteringTimeRange;
        this.timeResolution = timeResolution;

        const permissions = await this.dataService.userPermission();

        await this.showImage(mediaId);
        await this.refreshSequence();
        const mediaIdAsInt = BigInt('0x' + mediaId);
        const previousMediaId = ShowSingleMediaComponent.bigint2objectId(this.prevIdMap.get(mediaIdAsInt));
        const nextMediaId = ShowSingleMediaComponent.bigint2objectId(this.nextIdMap.get(mediaIdAsInt));
        this.previousMediaContent = previousMediaId
            ? this.dataService.getImage(this.albumId, previousMediaId, this.bigImageSize)
            : undefined;
        this.nextMediaContent = nextMediaId
            ? this.dataService.getImage(this.albumId, nextMediaId, this.bigImageSize)
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
        return this.mediaResolver.lookupVideo(this.albumId, mediaId, this.bigImageSize);
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

        const filteringKeywords: string[] = this.filteringKeywords;
        const filteringTimeRange: [number, number] = this.filteringTimeRange;
        const keywordCombine: KeywordCombine = this.keywordCombine;
        const filter = createFilter(filteringKeywords, keywordCombine, filteringTimeRange);
        const [, albumEntries] = await this.dataService.listAlbum(this.albumId, filter);
        let lastAlbumId: BigInt;
        const timeResolution = this.timeResolution;
        const sortedEntries = filterTimeResolution(albumEntries, timeResolution);
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
        if (mediaId === undefined) {
            return;
        }
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
                        ? this.dataService.getImage(this.albumId, nextMediaId, this.bigImageSize)
                        : undefined;
                } else if (mediaId === this.previousMediaId) {
                    // move fast backward
                    this.nextMediaContent = this.currentMediaContent;
                    this.currentMediaContent = this.previousMediaContent;
                    this.previousMediaContent = previousMediaId
                        ? this.dataService.getImage(this.albumId, previousMediaId, this.bigImageSize)
                        : undefined;
                } else if (this.mediaId !== mediaId) {
                    // move anywhere else
                    this.currentMediaContent = this.dataService.getImage(this.albumId, mediaId, this.bigImageSize);
                    this.previousMediaContent = previousMediaId
                        ? this.dataService.getImage(this.albumId, previousMediaId, this.bigImageSize)
                        : undefined;
                    this.nextMediaContent = nextMediaId
                        ? this.dataService.getImage(this.albumId, nextMediaId, this.bigImageSize)
                        : undefined;
                }
                this.mediaId = mediaId;
                this.metadata = albumEntry;
                this.titleService.setTitle(albumEntry.name);
                this.location.replaceState(this.mediaPath(mediaId).pathname);
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

    private mediaPath(mediaId: string): URL {
        return createMediaPath(
            this.albumId,
            mediaId,
            this.filteringKeywords,
            this.keywordCombine,
            this.filteringTimeRange,
            this.timeResolution);
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
            url: this.mediaPath(this.mediaId).toString()
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
