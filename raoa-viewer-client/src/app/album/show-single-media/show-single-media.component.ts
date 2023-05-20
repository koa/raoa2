import {ChangeDetectorRef, Component, ElementRef, HostListener, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute, ParamMap} from '@angular/router';
import {MediaResolverService} from '../service/media-resolver.service';
import {Location} from '@angular/common';
import {LoadingController} from '@ionic/angular';
import {HttpClient} from '@angular/common/http';
import {ServerApiService} from '../../service/server-api.service';
import {DomSanitizer, SafeUrl, Title} from '@angular/platform-browser';
import {createFilter, DataService, filterTimeResolution} from '../../service/data.service';
import {AlbumEntryData} from '../../service/storage.service';
import {combineLatest, from, lastValueFrom, race} from 'rxjs';
import {MultiWindowService} from 'ngx-multi-window';
import {ShowMedia} from '../../interfaces/show-media';
import {map} from 'rxjs/operators';


export type KeywordCombine = 'and' | 'or';
export type TimeRange = [number, number] | undefined;

export interface FilterQueryParams {
    keyword: string | undefined;
    c: string | undefined;
    tf: number | undefined;
    tu: number | undefined;
    tr: number | undefined;
    t: string | undefined;
}

export function createFilterQueryParams(
    keywords: string[],
    keywordCombine: KeywordCombine,
    showPhotos: boolean,
    showVideos: boolean,
    filteringTimeRange: TimeRange,
    timeResolution: number): FilterQueryParams {
    const [keyword, c] = (keywords !== undefined && keywords.length > 0)
        ? [keywords.map(v => encodeURI(v)).join(','), keywordCombine === 'and' ? 'a' : 'o']
        : [undefined, undefined];
    const [tf, tu] = filteringTimeRange !== undefined
        ? filteringTimeRange
        : [undefined, undefined];
    const tr = timeResolution > 0 ? timeResolution : undefined;
    const t = !showPhotos ? 'v' : !showVideos ? 'p' : undefined;
    return {
        keyword, c,
        tf, tu,
        tr,
        t
    };

}

export function updateSearchParams(
    keywords: string[],
    keywordCombine: KeywordCombine,
    filteringTimeRange: TimeRange | undefined,
    timeResolution: number,
    params: URLSearchParams, showPhotos: boolean, showVideos: boolean) {
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
    if (!showVideos) {
        params.set('t', 'p');
    } else if (!showPhotos) {
        params.set('t', 'v');
    } else {
        params.delete('t');
    }
}

export function collectFilterParams(
    keywords: string[],
    keywordCombine: KeywordCombine,
    filteringTimeRange: TimeRange,
    timeResolution: number, showPhotos: boolean, showVideos: boolean):
    URLSearchParams {
    const params = new URLSearchParams();
    updateSearchParams(keywords, keywordCombine, filteringTimeRange, timeResolution, params, showPhotos, showVideos);
    return params;
}


export function createMediaPath(
    albumId: string,
    mediaId: string,
    keywords: string[],
    keywordCombine: KeywordCombine,
    showPhotos: boolean,
    showVideos: boolean,
    filteringTimeRange: TimeRange,
    timeResolution: number): URL {
    const path = new URL(window.location.href);
    path.pathname = '/album/' + albumId + '/media/' + mediaId;
    updateSearchParams(keywords, keywordCombine, filteringTimeRange, timeResolution, path.searchParams, showPhotos, showVideos);
    return path;
}


export function parseFilterParams(queryParam: ParamMap): [string[], KeywordCombine, TimeRange, number, boolean, boolean] {
    const filteringKeyword = queryParam.get('keyword') || undefined;
    const combination = queryParam.get('c');
    const tf = queryParam.get('tf');
    const tu = queryParam.get('tu');
    const tr = queryParam.get('tr');
    const t = queryParam.get('t');
    const showPhotos = t === null || t === 'p';
    const showVideos = t === null || t === 'v';
    return [
        filteringKeyword?.split(',').map(e => decodeURI(e)) ?? [],
        combination ? combination === 'a' ? 'and' : 'or' : 'and',
        tf && tu ? [Number.parseInt(tf, 10), Number.parseInt(tu, 10)] : undefined,
        tr ? Number.parseInt(tr, 10) : 0, showPhotos, showVideos
    ];
}

@Component({
    selector: 'app-show-single-media',
    templateUrl: './show-single-media.component.html',
    styleUrls: ['./show-single-media.component.css'],
})
export class ShowSingleMediaComponent implements OnInit {
    public autoCompleteKwCandidates: string[] = [];
    public albumId: string;
    public mediaId: string;
    public previousMediaId: string;
    public nextMediaId: string;
    public currentMediaContent: Promise<SafeUrl> = undefined;
    public previousMediaContent: Promise<SafeUrl> = undefined;
    public nextMediaContent: Promise<SafeUrl> = undefined;
    public supportShare: boolean;
    public metadata: AlbumEntryData;
    public showDetails: 'Metadata' | 'Keyword' | null = null;
    public albumKeywords: string[] = [];
    public allKnownAlbumKeywords = new Set<string>();
    public currentSelectedKeywords = new Set<string>();
    public canEdit = false;
    public currentIsVideo = false;
    public bigImageSize = 1600;
    public playVideo = false;
    public title = '';
    public inputKeyword = '';
    @ViewChild('imageSlider', {static: true})
    private imageSlider: ElementRef | undefined;
    @ViewChild('videoPlayer')
    private videoPlayer: ElementRef<HTMLVideoElement> | undefined;
    @ViewChild('videoRoot') private element: ElementRef<HTMLDivElement>;
    private filteringKeywords: string[];
    private keywordCombine: KeywordCombine = 'and';
    private filteringTimeRange: [number, number] | undefined;
    private nextIdMap: Map<BigInt, BigInt> = new Map<BigInt, BigInt>();
    private prevIdMap: Map<BigInt, BigInt> = new Map<BigInt, BigInt>();
    private timeResolution = 0;
    public canPresent = false;
    public isPresenting = false;
    isDownloadPopoverOpen = false;
    isSharePopoverOpen = false;
    private showPhotos = true;
    private showVideos = true;
    currentVideoUrl: SafeUrl = undefined;
    plaingVideo = false;

    constructor(private activatedRoute: ActivatedRoute,
                private mediaResolver: MediaResolverService,
                private dataService: DataService,
                private changeDetectorRef: ChangeDetectorRef,
                private location: Location,
                private http: HttpClient,
                private serverApi: ServerApiService,
                private loadingController: LoadingController,
                private titleService: Title,
                private multiWindowService: MultiWindowService,
                private sanitizer: DomSanitizer,
    ) {
        let hackNavi: any;
        hackNavi = window.navigator;
        this.supportShare = hackNavi.share !== undefined;
    }

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
        this.multiWindowService.onWindows().subscribe(windows => {
            let canPresent = false;
            for (const window of windows) {
                if (window.id !== this.multiWindowService.id) {
                    canPresent = true;
                }
            }
            this.canPresent = canPresent;
            this.changeDetectorRef.detectChanges();
        });
        combineLatest([this.activatedRoute.paramMap, this.activatedRoute.queryParamMap]).subscribe(async data => {
            const paramMap = data[0];
            const queryParam = data[1];

            this.albumId = paramMap.get('id');
            const mediaId = paramMap.get('mediaId');
            const [
                filteringKeywords,
                keywordCombine,
                filteringTimeRange,
                timeResolution,
                showPhotos,
                showVideos
            ] = parseFilterParams(queryParam);

            this.filteringKeywords = filteringKeywords;
            this.keywordCombine = keywordCombine;
            this.filteringTimeRange = filteringTimeRange;
            this.timeResolution = timeResolution;
            this.showPhotos = showPhotos;
            this.showVideos = showVideos;

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

            this.nextMediaId = nextMediaId;
            this.previousMediaId = previousMediaId;
            this.canEdit = permissions.canEdit;
            this.changeDetectorRef.detectChanges();
            await this.refreshControls();
        });
    }


    async loadVideo(mediaId: string): Promise<SafeUrl> {
        if (mediaId === undefined) {
            return undefined;
        }
        return this.dataService.getVideo(this.albumId, mediaId, this.bigImageSize);
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
        const filter = createFilter(filteringKeywords, keywordCombine, filteringTimeRange, this.showPhotos, this.showVideos);
        const knownKeywords = new Set<string>();
        const [albumData, albumEntries] = await this.dataService.listAlbum(this.albumId, entryCandidate => {
            entryCandidate.keywords.forEach(kw => knownKeywords.add(kw));
            return filter(entryCandidate);
        });
        let lastAlbumId: BigInt | undefined;
        const timeResolution = this.timeResolution;
        const sortedEntries = filterTimeResolution(albumEntries, timeResolution);
        for (const entry of sortedEntries) {
            const myId: BigInt = BigInt('0x' + entry.albumEntryId);
            if (lastAlbumId !== undefined) {
                this.nextIdMap.set(lastAlbumId, myId);
                this.prevIdMap.set(myId, lastAlbumId);
            }
            lastAlbumId = myId;
        }
        this.title = albumData.title;
        this.allKnownAlbumKeywords = knownKeywords;
        this.changeDetectorRef.detectChanges();
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
                translucent: true,
                showBackdrop: true
            }).then(ind => {
                waitIndicator = ind;
                ind.present();
            });
        }, 500);
        try {
            const mediaIdAsInt = BigInt('0x' + mediaId);
            const previousMediaId = ShowSingleMediaComponent.bigint2objectId(this.prevIdMap.get(mediaIdAsInt));
            const nextMediaId = ShowSingleMediaComponent.bigint2objectId(this.nextIdMap.get(mediaIdAsInt));
            const [[albumEntry, keywords], diashowEnabled] = await Promise.all(
                [this.dataService.getAlbumEntry(this.albumId, mediaId),
                    this.dataService.isDiashowEnabled(this.albumId, mediaId)
                ]);

            this.isPresenting = diashowEnabled;
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
            const url = this.mediaPath(mediaId);
            this.location.replaceState(url.pathname, url.searchParams.toString());
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
            this.currentVideoUrl = undefined;
            await this.refreshControls();
            this.changeDetectorRef.detectChanges();
        } finally {
            window.clearTimeout(waitTimeoutHandler);
            if (waitIndicator !== undefined) {
                waitIndicator.dismiss();
            }
        }

    }


    async slided() {
        const swiper = this.imageSlider?.nativeElement.swiper;
        const index = swiper?.activeIndex;
        if (this.playVideo) {
            this.plaingVideo = false;
            this.playVideo = false;
            if (this.videoPlayer) {
                await this.videoPlayer.nativeElement.pause();
            }
        }
        if (index === 2) {
            if (this.nextMediaId !== undefined) {
                await this.showImage(this.nextMediaId);
            } else {
                swiper.slidePrev(500, false);
            }
        } else if (index === 0) {
            if (this.previousMediaId !== undefined) {
                await this.showImage(this.previousMediaId);
            } else {
                swiper.slideNext(500, false);
            }
        }
    }

    async downloadCurrentFile(entryId: string, metadata: AlbumEntryData) {
        if (this.currentIsVideo) {
            this.isDownloadPopoverOpen = true;
        } else {
            const imageBlob = await this.loadOriginal(entryId);
            const a = document.createElement('a');
            const objectUrl = URL.createObjectURL(imageBlob);
            a.href = objectUrl;
            a.download = metadata.name || 'original.jpg';
            a.click();
            a.remove();
            URL.revokeObjectURL(objectUrl);
        }
    }

    async downloadVideo(entryId: string, metadata: AlbumEntryData, resolution: number) {
        const imageBlob = await this.dataService.getVideoBlob(this.albumId, entryId, resolution);
        const objectUrl = URL.createObjectURL(imageBlob.data);
        const a = document.createElement('a');
        a.href = objectUrl;
        if (metadata.name) {
            const filename = metadata.name;
            if (filename.toLowerCase().endsWith('.mp4')) {
                a.download = filename;
            } else {
                a.download = filename + '.mp4';
            }
        } else {
            a.download = 'video.mp4';
        }
        a.click();
        a.remove();
        URL.revokeObjectURL(objectUrl);
    }

    async shareCurrentFile(entryId: string, metadata: AlbumEntryData) {
        if (this.currentIsVideo) {
            this.isSharePopoverOpen = true;
        } else {
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
            await navigator.share(data).catch(error => {
                console.log('Share error');
                console.log(error.class);
                console.log(error);
            });
        }
    }

    async shareVideo(entryId: string, metadata: AlbumEntryData, resolution: number) {
        const contentType = 'video/mp4';
        const filename = metadata.name || 'video.mp4';
        const videoBlob = (await this.dataService.getVideoBlob(this.albumId, entryId, resolution)).data;
        // console.log('image loaded');
        const lastModified = metadata.created;
        const file = new File([videoBlob], filename, {type: contentType, lastModified});
        const data: ShareData = {
            title: filename,
            files: [file],
            url: this.mediaPath(this.mediaId).toString()
        };
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

    public async removeKeyword(keyword: string) {
        await this.dataService.removeKeyword(this.albumId, [this.mediaId], [keyword]);
        await this.showImage(this.mediaId);
    }

    public updateAutocomplete() {
        const searchText = this.inputKeyword.toLowerCase();
        const kwCandiates: string[] = [];
        if (searchText.length > 0) {
            this.allKnownAlbumKeywords.forEach(kw => {
                if (this.currentSelectedKeywords.has(kw)) {
                    return;
                }
                if (kw.toLowerCase().indexOf(searchText) >= 0) {
                    kwCandiates.push(kw);
                }
            });
        }
        kwCandiates.sort((a, b) => a.localeCompare(b));
        this.autoCompleteKwCandidates = kwCandiates;
    }

    public async addKeyword(keyword: string) {
        this.autoCompleteKwCandidates = [];
        this.inputKeyword = '';
        this.allKnownAlbumKeywords.add(keyword);
        await this.dataService.addKeyword(this.albumId, [this.mediaId], [keyword]);
        await this.showImage(this.mediaId);
    }

    private async refreshControls() {
        const swiper = this.imageSlider?.nativeElement.swiper;
        if (!swiper) {
            return;
        }
        swiper.allowSlidePrev = this.previousMediaId !== undefined;
        swiper.allowSlideNext = this.nextMediaId !== undefined;
        swiper.activeIndex = 1;
        swiper.update();
    }

    private mediaPath(mediaId: string): URL {
        return createMediaPath(
            this.albumId,
            mediaId,
            this.filteringKeywords,
            this.keywordCombine,
            this.showPhotos, this.showVideos,
            this.filteringTimeRange,
            this.timeResolution);
    }

    private async loadOriginal(entryId: string): Promise<Blob> {
        const loadingOriginalIndicator = await this.loadingController.create({message: 'Original wird geladen...'});
        const src = this.mediaResolver.lookupOriginal(this.albumId, entryId);
        await loadingOriginalIndicator.present();
        try {
            return await this.http.get(src, {responseType: 'blob'}).toPromise();
        } finally {
            await loadingOriginalIndicator.dismiss();
        }
    }

    private async loadVideoBlob(entryId: string, maxLength: number): Promise<Blob | undefined> {
        const loadingVideoIndicator = await this.loadingController.create({
            message: 'Video wird geladen...',
            showBackdrop: true,
            backdropDismiss: true
        });
        const src = this.mediaResolver.lookupVideo(this.albumId, entryId, maxLength);
        await loadingVideoIndicator.present();

        try {
            const cancelled = from(loadingVideoIndicator.onDidDismiss());
            const loaded = this.http.get(src, {responseType: 'blob'});
            const result = await lastValueFrom(race(cancelled.pipe(map(() => 'none')), loaded));
            if (typeof result === 'object') {
                return result as Blob;
            } else {
                return undefined;
            }
        } finally {
            await loadingVideoIndicator.dismiss();
        }
    }

    public async presentCurrentMedia(enable: boolean) {
        if (enable) {
            await this.dataService.appendDiashow(this.albumId, this.mediaId);
        } else {
            await this.dataService.removeDiashow(this.albumId, this.mediaId);
        }
        if (this.canPresent) {
            const event: ShowMedia = {
                albumId: this.albumId, mediaId: this.mediaId
            };
            const myId = this.multiWindowService.id;
            for (const knownWindow of this.multiWindowService.getKnownWindows()) {
                if (knownWindow.id === myId) {
                    continue;
                }
                this.multiWindowService.sendMessage(knownWindow.id, 'showMedia', event);
            }
        }
        const diashowEnabled = await this.dataService.isDiashowEnabled(this.albumId, this.mediaId);
        this.isPresenting = diashowEnabled;
        this.changeDetectorRef.detectChanges();
    }

    videoPlay($event: Event) {
        this.plaingVideo = true;
    }

    async startPlayVideo() {
        if (!this.playVideo) {
            const loadingVideoIndicator = await this.loadingController.create({
                message: 'Video wird geladen...',
                showBackdrop: true,
                backdropDismiss: true
            });
            await loadingVideoIndicator.present();
            try {
                this.playVideo = true;
                this.currentVideoUrl = await this.loadVideo(this.mediaId);
            } finally {
                await loadingVideoIndicator.dismiss();
            }
            this.changeDetectorRef.detectChanges();
        }
        if (this.videoPlayer) {
            await this.videoPlayer.nativeElement.play();
        }
    }

    videoStop($event: Event) {
        this.plaingVideo = false;
    }
}
