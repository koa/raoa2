import {Component, ElementRef, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {ServerApiService} from '../../service/server-api.service';
import {AlbumContentGQL, SingleAlbumMutateGQL, UserPermissionsGQL} from '../../generated/graphql';
import {HttpClient} from '@angular/common/http';
import {MediaResolverService} from '../service/media-resolver.service';
import {Location} from '@angular/common';
import {IonContent, LoadingController, MenuController, ToastController} from '@ionic/angular';
import {Title} from '@angular/platform-browser';
import {AlbumEntryData} from '../../service/storage.service';
import {createFilter, DataService, filterTimeResolution} from '../../service/data.service';
import {defer, Observable} from 'rxjs';
import {
    createFilterQueryParams,
    createMediaPath,
    KeywordCombine,
    parseFilterParams,
    TimeRange,
    updateSearchParams
} from '../show-single-media/show-single-media.component';


@Component({
    selector: 'app-album',
    templateUrl: './album.page.html',
    styleUrls: ['./album.page.css'],
})
export class AlbumPage implements OnInit {
    public allDateCounts = 0;
    private touchTimer: number | undefined;

    constructor(private activatedRoute: ActivatedRoute,
                private serverApi: ServerApiService,
                private albumContentGQL: AlbumContentGQL,
                private singleAlbumMutateGQL: SingleAlbumMutateGQL,
                private dataService: DataService,
                private ngZone: NgZone,
                private http: HttpClient,
                private mediaResolver: MediaResolverService,
                private location: Location,
                private loadingController: LoadingController,
                private menuController: MenuController,
                private titleService: Title,
                private router: Router,
                private toastController: ToastController,
                private userPermissionsGQL: UserPermissionsGQL
    ) {
    }

    public syncEnabled: boolean;
    public fnCompetitionId: string;
    public albumId: string;
    public title: string;
    public rows: Array<TableRow> = [];
    public days: number[] = [];
    // private keywords = new Set<string>();
    public canAddKeyword = new Set<string>();
    public canRemoveKeywords = new Set<string>();
    public sortedKeywords: string[] = [];
    public maxWidth = 8;
    public filteringKeywords = new Set<string>();
    public keywordCombinator: KeywordCombine = 'and';
    public selectedEntries = new Set<string>();
    public selectableDates: number[] = [];
    public elementWidth = 10;
    public enableSettings = false;
    public daycount = 0;
    public timestamp = 0;
    public canEdit = false;
    public newTag = '';
    // public pendingRemoveKeywords: Map<string, Set<string>> = new Map<string, Set<string>>();
    public filterTimeStep = 60 * 1000;
    public pendingMutationCount = 0;
    public keywordCounters: Map<string, number> = new Map<string, number>();
    public dayCounters = new Map<number, number>();
    /*
    private sortKeywords() {
        this.sortedKeywords = [];
        this.keywords.forEach(keyword => this.sortedKeywords.push(keyword));
        this.sortedKeywords.sort((k1, k2) => k1.localeCompare(k2));
    }

     */
    public syncing = false;
    private filteringTimeRange: TimeRange;
    private loadingElement: HTMLIonLoadingElement;
    private lastSelectedIndex: number | undefined = undefined;
    private lastScrollPos = 0;
    @ViewChild('imageList') private imageListElement: ElementRef<HTMLDivElement>;
    @ViewChild('content') private contentElement: IonContent;
    // public pendingAddKeywords: Map<string, Set<string>> = new Map<string, Set<string>>();
    private sortedEntries: AlbumEntryData[] = [];
    private waitCount = 0;
    public selectedDay: number | undefined;

    private static findIndizesOf(rows: Array<TableRow>, timestamp: number): [number, number, number] | undefined {
        for (let rowIndex = 0; rowIndex < rows.length; rowIndex++) {
            const row = rows[rowIndex];
            if (row.kind === 'images') {
                if (row.endTimestamp < timestamp) {
                    continue;
                }
                const blocks = row.blocks;
                for (let blockIndex = 0; blockIndex < blocks.length; blockIndex++) {
                    const block = blocks[blockIndex];
                    const shapes = block.shapes;
                    for (let shapeIndex = 0; shapeIndex < shapes.length; shapeIndex++) {
                        const shape = shapes[shapeIndex];
                        if (shape.timestamp >= timestamp) {
                            return [rowIndex, blockIndex, shapeIndex];
                        }
                    }
                }
            }
        }
        return undefined;
    }

    public async resized() {
        if (this.elementWidth === this.imageListElement.nativeElement.clientWidth) {
            return;
        }
        const screenSize = Math.max(window.screen.width, window.screen.height);
        const storeScreenSizePromise = this.dataService.storeScreenSize(screenSize);

        const timestampBefore = this.timestamp;
        this.elementWidth = this.imageListElement.nativeElement.clientWidth;

        const maxRowHeight = 2 * Math.sqrt((window.innerWidth * window.innerHeight) / 6 / 6);
        const newMaxWidth = Math.min(10, Math.round(this.elementWidth / (Math.min(100 * window.devicePixelRatio, maxRowHeight)) * 4) / 4);
        if (this.maxWidth !== newMaxWidth) {
            this.maxWidth = newMaxWidth;
            await this.calculateRows();
        }
        await storeScreenSizePromise;
        this.scrollToTimestamp(timestampBefore);
    }

    async onScroll(e: CustomEvent) {
        const detail = e.detail;
        const foundTimestamp = this.findCurrentTimestamp();
        if (foundTimestamp !== undefined) {
            this.timestamp = foundTimestamp;
        }
        this.lastScrollPos = detail.scrollTop;
    }

    async enterWait(reason: WaitReason): Promise<void> {
        const newCount = ++this.waitCount;
        if (newCount === 1 && this.loadingElement === undefined) {
            let message = 'Bitte Warten ...';
            switch (reason) {
                case WaitReason.LOAD:
                    message = this.title ? ('Lade ' + this.title + ' ...') : 'Lade Album ...';
                    break;
                case WaitReason.STORE:
                    message = this.title ? ('Speichere ' + this.title + ' ...') : 'Speichere Album ...';
                    break;
            }
            const element = await this.loadingController.create({message});
            await element.present();
            if (this.waitCount === 0) {
                await element.dismiss();
            } else {
                this.loadingElement = element;
            }
        }
    }

    async leaveWait(): Promise<void> {
        const newCount = --this.waitCount;
        if (newCount === 0) {
            if (this.loadingElement !== undefined) {
                await this.loadingElement.dismiss();
                this.loadingElement = undefined;
            }
        }
    }

    async ngOnInit() {
        this.titleService.setTitle('Album laden');
        this.activatedRoute.queryParamMap.subscribe(async params => {

            const [filteringKeywords, keywordCombine, filteringTimeRange, timeResolution] = parseFilterParams(params);
            let refreshNeeded = false;
            // if (this.albumId) {
            const keywordSet = new Set<string>(filteringKeywords);
            if (this.filteringKeywords !== keywordSet) {
                this.filteringKeywords = keywordSet;
                this.lastSelectedIndex = undefined;
                refreshNeeded = true;
            }
            if (this.keywordCombinator !== keywordCombine) {
                this.keywordCombinator = keywordCombine;
                refreshNeeded = true;
            }
            if (this.filteringTimeRange !== filteringTimeRange) {
                this.filteringTimeRange = filteringTimeRange;
                if (filteringTimeRange === undefined) {
                    this.selectedDay = undefined;
                } else {
                    this.selectedDay = filteringTimeRange[0];
                }
                refreshNeeded = true;
            }
            if (this.filterTimeStep !== timeResolution) {
                this.filterTimeStep = timeResolution;
                refreshNeeded = true;
            }
            // }
            const timestamp = params.get('timestamp');
            if (timestamp !== undefined) {
                this.timestamp = Number.parseInt(timestamp, 10);
                refreshNeeded = true;
            }
            if (refreshNeeded) {
                await this.refresh();
            }

        });
        this.activatedRoute.paramMap.subscribe(async params => {
            const id = params.get('id');
            if (this.albumId !== id) {
                this.albumId = id;
                this.selectedEntries.clear();
                this.lastSelectedIndex = undefined;
                await this.refresh();
            }
        });
    }

    public scrollToTimestamp(timestamp: number) {
        this.timestamp = timestamp;
        const foundIndices = AlbumPage.findIndizesOf(this.rows, timestamp);
        if (foundIndices !== undefined) {
            const rowIndex = foundIndices[0];
            const blockIndex = foundIndices[1];
            // console.log('scroll to ' + rowIndex + ', ' + blockIndex);
            // const shapeIndex = foundIndices[2];
            window.setTimeout(() => {
                const rowElement = document.getElementById('row-' + rowIndex);
                const observer = new MutationObserver((mutations => {
                    const blockElement = document.getElementById('block-' + rowIndex + '-' + blockIndex);
                    if (blockElement) {
                        blockElement.scrollIntoView();
                        observer.disconnect();
                    }
                }));
                observer.observe(rowElement, {childList: true});
                // console.log(rowElement);
                rowElement.scrollIntoView();
                const blockElementDirect = document.getElementById('block-' + rowIndex + '-' + blockIndex);
                if (blockElementDirect) {
                    blockElementDirect.scrollIntoView();
                    observer.disconnect();
                }
            }, 100);
        }
    }


    async scrollTo(id: string) {
        const y = document.getElementById(id).offsetTop;
        await this.contentElement.scrollToPoint(0, y);
        await this.menuController.close();
    }

    async filter(keyword: string) {
        if (this.filteringKeywords.has(keyword)) {
            this.filteringKeywords.delete(keyword);
        } else {
            this.filteringKeywords.add(keyword);
        }
        await this.refreshFilters();
    }

    private updateFilterQueryParams() {
        const kwlist: string[] = [];
        for (const kw of this.filteringKeywords) {
            kwlist.push(kw);
        }
        const url = new URL(window.location.href);
        for (const param of ['keyword', 'c']) {
            url.searchParams.delete(param);
        }
        updateSearchParams(kwlist, this.keywordCombinator, this.filteringTimeRange, this.filterTimeStep, url.searchParams);
        this.location.replaceState(url.pathname, url.searchParams.toString());
    }

    public createEntryLink(shape: Shape): string {
        const filteringKeywords: string[] = [];
        this.filteringKeywords.forEach(kw => filteringKeywords.push(kw));
        return createMediaPath(this.albumId,
            shape.entry.albumEntryId,
            filteringKeywords,
            'and',
            this.filteringTimeRange,
            this.filterTimeStep);
    }

    queryParams() {
        const ret = new Map<string, string>();
        if (this.filteringKeywords.size > 0) {
            const kwlist: string[] = [];
            this.filteringKeywords.forEach(kw => kwlist.push(encodeURIComponent(kw)));
            ret.set('keyword', kwlist.join(','));
        }
        return ret;
    }

    public async imageClicked(blockPart: ImageBlock, shape: Shape, $event: MouseEvent) {
        const shiftKey = $event.shiftKey;
        const ctrlKey = $event.ctrlKey;
        if (ctrlKey || shiftKey) {
            const entryId = shape.entry.albumEntryId;
            const selectedIndex = shape.entryIndex;
            if (shiftKey) {
                if (this.lastSelectedIndex !== undefined) {
                    const selectFrom = Math.min(this.lastSelectedIndex, selectedIndex);
                    const selectUntil = Math.max(this.lastSelectedIndex, selectedIndex);
                    const slice = this.sortedEntries.slice(selectFrom, selectUntil + 1);
                    const allSelected = this.selectedEntries.has(this.sortedEntries[this.lastSelectedIndex].albumEntryId);
                    if (!allSelected) {
                        slice.forEach(entry => this.selectedEntries.delete(entry.albumEntryId));
                    } else {
                        slice.forEach(entry => this.selectedEntries.add(entry.albumEntryId));
                    }
                    this.lastSelectedIndex = undefined;
                }
            } else {
                this.lastSelectedIndex = selectedIndex;
                if (this.selectedEntries.has(entryId)) {
                    this.selectedEntries.delete(entryId);
                } else {
                    this.selectedEntries.add(entryId);
                }
            }
            await this.refreshPossibleKeywords();
        } else {
            await this.goToAlbumEntry(shape);
        }
    }

    public touchStart(blockPart: ImageBlock, shape: Shape, $event: TouchEvent) {
        $event.preventDefault();
        if (this.touchTimer !== undefined) {
            clearTimeout(this.touchTimer);
        }
        this.touchTimer = setTimeout(() => {
            const shiftKey = $event.shiftKey;
            this.touchTimer = undefined;
            const entryId = shape.entry.albumEntryId;
            const selectedIndex = shape.entryIndex;
            this.ngZone.run(() => {
                this.lastSelectedIndex = selectedIndex;
                if (this.selectedEntries.has(entryId)) {
                    this.selectedEntries.delete(entryId);
                } else {
                    this.selectedEntries.add(entryId);
                }
            });
        }, 600);
        console.log('start', shape, $event);
    }

    public async touchEnd(blockPart: ImageBlock, shape: Shape, $event: TouchEvent) {
        if (this.touchTimer !== undefined) {
            clearTimeout(this.touchTimer);
            await this.goToAlbumEntry(shape);
        }
    }


    private async goToAlbumEntry(shape: Shape) {
        const path = ['album', this.albumId, 'media', shape.entry.albumEntryId];

        const filteringKeywords: string[] = [];
        this.filteringKeywords.forEach(kw => filteringKeywords.push(kw));


        const urlTree = this.router.createUrlTree(path, {
            queryParams: createFilterQueryParams(filteringKeywords,
                this.keywordCombinator,
                this.filteringTimeRange,
                this.filterTimeStep)
        });
        await this.router.navigateByUrl(urlTree);
    }

    async tagAdded($event: any) {
        const newKeyword = this.newTag;
        this.newTag = '';
        await this.addTag(newKeyword);
    }

    public selectionCanAdd(keyword: string): boolean {
        return this.canAddKeyword.has(keyword);
    }

    public selectionCanRemove(keyword: string): boolean {
        return this.canRemoveKeywords.has(keyword);
    }

    async addTag(keyword: string) {
        await this.dataService.addKeyword(this.albumId, this.selectedEntriesAsArray(), [keyword]);
        await this.refresh();
    }

    public async storeMutation() {
        await this.enterWait(WaitReason.STORE);
        try {
            await this.dataService.storeMutations(this.albumId);
            await this.refresh();
        } finally {
            await this.leaveWait();
        }
    }

    async removeTag(keyword: string) {
        await this.dataService.removeKeyword(this.albumId, this.selectedEntriesAsArray(), [keyword]);
        await this.refresh();
    }


    async refreshPendingMutations() {
        const newValue = await this.dataService.countPendingMutations(this.albumId);
        this.ngZone.run(() => {
            this.pendingMutationCount = newValue;
        });
    }

    clearSelection() {
        this.selectedEntries.clear();
    }

    async resetMutation(): Promise<void> {
        await this.dataService.clearPendingMutations(this.albumId);
        await this.refreshPendingMutations();
    }

    async setResolution(time: number) {
        if (time === this.filterTimeStep) {
            return;
        }
        this.filterTimeStep = time;
        this.updateFilterQueryParams();
        await this.refresh();
    }

    public isResolution(res: number): boolean {
        return this.filterTimeStep === res;
    }

    public async syncAlbum(): Promise<void> {
        await this.dataService.setSync(this.albumId, !this.syncEnabled);
        await this.refresh();
        /*
        this.syncing = true;
        try {
            const albumContent: [AlbumData, AlbumEntryData[]] = await this.dataService.listAlbum(this.albumId);
            let batch: Promise<void>[] = [];
            let lastPromise = Promise.resolve();
            for (const entry of albumContent[1]) {
                batch.push(this.dataService.getImage(this.albumId, entry.albumEntryId, 3200).then());
                if (batch.length >= 10) {
                    await lastPromise;
                    lastPromise = Promise.all(batch).then();
                    batch = [];
                }
            }
        } finally {
            this.ngZone.run(() => this.syncing = false);
        }
         */
    }

    private findCurrentTimestamp() {
        const rows: HTMLCollectionOf<Element> = document.getElementsByClassName('image-row');
        let bestResult = Number.MAX_SAFE_INTEGER;
        let bestElement;
        for (let i = 0; i < rows.length; i++) {
            const element: Element = rows.item(i);
            const bottom = element.getBoundingClientRect().bottom;
            if (bottom > 0 && bottom < bestResult) {
                bestResult = bottom;
                bestElement = element;
            }
        }
        if (bestElement) {
            const foundTimestamp = Number.parseInt(bestElement.getAttribute('timestamp'), 10);
            this.setParam('timestamp', foundTimestamp.toString());
            return foundTimestamp;
        }
        return undefined;
    }

    private setParam(param: string, value: string | undefined) {
        const url = new URL(window.location.href);
        if (value === undefined) {
            url.searchParams.delete(param);
        } else {
            url.searchParams.set(param, value);
        }
        this.location.replaceState(url.pathname, url.searchParams.toString());
    }

    private async refresh() {
        await this.enterWait(WaitReason.LOAD);
        try {
            const userPermissions = await this.dataService.userPermission();
            const keywords: string[] = [];
            this.filteringKeywords.forEach(kw => keywords.push(kw));
            const keywordFilter = createFilter(keywords, 'and', undefined);
            const rowCountBefore = this.sortedEntries.length;
            const knownKeywords = new Map<string, number>();
            const foundDates = new Map<number, number>();
            const timeFilter = createFilter([], 'and', this.filteringTimeRange);
            // const keywordTimeFilter = createFilter(keywords, 'and', this.filteringTimeRange);
            let allDateCounts = 0;
            const [album, entries, albumSettings] = await this.dataService.listAlbum(this.albumId, entry => {
                const keywordFiltered = keywordFilter(entry);
                if (!keywordFiltered) {
                    return false;
                }
                const timeFiltered = timeFilter(entry);
                if (timeFiltered) {
                    entry.keywords.forEach(kw => knownKeywords.set(kw, (knownKeywords.get(kw) || 0) + 1));
                }
                if (entry.created) {
                    const timestamp = new Date(entry.created);
                    const localTimestamp = entry.created - timestamp.getTimezoneOffset() * 60 * 1000;
                    const remainder = localTimestamp % (24 * 3600 * 1000);
                    const day = entry.created - remainder;
                    foundDates.set(day, (foundDates.get(day) || 0) + 1);
                }
                allDateCounts += 1;
                return timeFiltered;
            });
            if (allDateCounts === 0 && this.filteringKeywords.size > 0) {
                this.filteringKeywords.clear();
                await this.refreshFilters();
                return;
            }
            const [newSortedKeywords, canAddKeywords, canRemoveKeywords] = await this.adjustKeywords(knownKeywords.keys());
            const hasPendingMutations = await this.dataService.countPendingMutations(this.albumId);
            this.ngZone.run(() => {
                this.titleService.setTitle(album.title);
                this.pendingMutationCount = hasPendingMutations;
                this.days = [];
                if (foundDates.size > 1) {
                    foundDates.forEach((count, timestamp) => this.days.push(timestamp));
                    this.days.sort();
                }
                this.allDateCounts = allDateCounts;
                this.dayCounters = foundDates;
                // filter pending keyword modifications
                // this.keywords.clear();
                /*
                entries.forEach(albumEntry => {
                    albumEntry.keywords.forEach(keyword => {
                        this.keywords.add(keyword);
                    });
                    const albumEntryId = albumEntry.albumEntryId;
                    if (this.pendingAddKeywords.has(albumEntryId)) {
                        const kwlist = this.pendingAddKeywords.get(albumEntryId);
                        albumEntry.keywords.forEach(kw => kwlist.delete(kw));
                        if (kwlist.size === 0) {
                            this.pendingAddKeywords.delete(albumEntryId);
                        }
                    }
                    if (this.pendingRemoveKeywords.has(albumEntryId)) {
                        const stillExistingKeywords = new Set<string>(albumEntry.keywords);
                        const kwlist = this.pendingRemoveKeywords.get(albumEntryId);
                        const newRemoveKeywords = new Set<string>();
                        for (const kw of kwlist) {
                            if (stillExistingKeywords.has(kw)) {
                                newRemoveKeywords.add(kw);
                            }
                        }
                        if (newRemoveKeywords.size === 0) {
                            this.pendingRemoveKeywords.delete(albumEntryId);
                        } else {
                            this.pendingRemoveKeywords.set(albumEntryId, newRemoveKeywords);
                        }
                    }
                });
                 */
                // this.sortKeywords();
                this.keywordCounters = knownKeywords;
                this.sortedKeywords = newSortedKeywords;
                this.canAddKeyword = canAddKeywords;
                this.canRemoveKeywords = canRemoveKeywords;
                this.title = album.title;
                this.syncEnabled = albumSettings ? albumSettings.syncOffline : false;
                this.sortedEntries = filterTimeResolution(entries, this.filterTimeStep);

                this.fnCompetitionId = album.fnchAlbumId;
                this.enableSettings = userPermissions.canManageUsers;
                this.canEdit = userPermissions.canEdit;

            });
            await this.calculateRows();
            if (rowCountBefore !== this.sortedEntries.length) {
                this.scrollToTimestamp(this.timestamp);
            }

        } finally {
            await this.leaveWait();
        }
    }

    private async calculateRows() {
        await this.enterWait(WaitReason.LOAD);
        const scrollPosBefore = this.lastScrollPos;
        this.ngZone.run(() => {
            const newRows: Array<TableRow> = [];
            // const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
            const optimalMediaCount = Math.sqrt(this.sortedEntries.length);
            let currentImageDate: number;
            let index = 0;
            let currentRow: Shape[] = [];
            let currentRowWidth = 0;
            let currentBlock: ImageBlock[] = [];
            let currentBlockLength = 0;
            let currentBlockMediaCount = 0;
            const flushRow = () => {
                if (currentRow.length > 0) {
                    const beginTimestamp = currentRow[0].entry.created;
                    const endTimestamp = currentRow[currentRow.length - 1].timestamp;
                    const width = currentRowWidth;
                    const scaledImages: Observable<string>[] = [];
                    currentRow.forEach(shape => {
                        scaledImages.push(defer(() => {
                            const imageHeight = this.elementWidth / width;
                            const imageWidth = imageHeight * shape.width;
                            const maxLength = Math.max(imageHeight, imageWidth);
                            return this.dataService.getImage(this.albumId, shape.entry.albumEntryId, maxLength);
                        }));
                    });
                    currentBlock.push({
                        shapes: currentRow,
                        width,
                        beginTimestamp,
                        endTimestamp,
                        scaledImages
                    });
                    currentBlockLength += 1 / width;
                    currentBlockMediaCount += currentRow.length;
                }
                currentRow = [];
                currentRowWidth = 0;
            };
            const flushBlock = () => {
                flushRow();
                if (currentBlock.length > 0) {
                    const endTimestamp = currentBlock[currentBlock.length - 1].endTimestamp;
                    newRows.push({kind: 'images', blocks: currentBlock, height: currentBlockLength, endTimestamp});
                }
                currentBlock = [];
                currentBlockLength = 0;
                currentBlockMediaCount = 0;
            };
            let dayCount = 0;
            const appender = (shape: Shape, date: number) => {
                if (currentImageDate === undefined || currentImageDate !== date) {
                    dayCount += 1;
                    flushBlock();
                    newRows.push({kind: 'timestamp', time: new Date(date), id: date.toString()});
                }
                currentImageDate = date;
                const totalWidth = currentRowWidth;
                if (totalWidth + shape.width > this.maxWidth) {
                    flushRow();
                    if (currentBlockMediaCount > optimalMediaCount) {
                        flushBlock();
                    }
                }
                currentRow.push(shape);
                currentRowWidth += shape.width;

            };
            this.sortedEntries
                .forEach(entry => {
                    const timestamp: number = entry.created;
                    const date = new Date(timestamp);
                    date.setHours(0, 0, 0, 0);
                    const imageDate = date.valueOf();
                    const imageWidth = entry.targetWidth / entry.targetHeight;
                    const imageShape: Shape = {
                        width: imageWidth,
                        entry,
                        entryIndex: index++,
                        isVideo: entry.entryType === 'video',
                        timestamp
                    };
                    appender(imageShape, imageDate);
                });
            flushBlock();
            this.rows = newRows;
            this.daycount = dayCount;
        });
        await this.leaveWait();
        // scroll back to original position
        setTimeout(() => this.contentElement.scrollToPoint(0, scrollPosBefore), 100);
    }

    private async adjustKeywords(knownKeywords: IterableIterator<string> | string[]): Promise<[string[], Set<string>, Set<string>]> {
        const sortedKeywords: string[] = [];
        for (const keyword of knownKeywords) {
            sortedKeywords.push(keyword);
        }
        sortedKeywords.sort((k1, k2) => k1.localeCompare(k2));
        const keywordStates = await this.dataService.currentKeywordStates(this.albumId, this.selectedEntries);
        const canRemoveKeywords = new Set<string>();
        const canAddKeywords = new Set<string>();
        keywordStates.forEach(state => {
            sortedKeywords.forEach(kw => {
                if ((state.existingKeywords.has(kw) || state.pendingAddKeywords.has(kw))
                    && !state.pendingRemoveKeywords.has(kw)) {
                    canRemoveKeywords.add(kw);
                }
                if (!state.existingKeywords.has(kw) && !state.pendingAddKeywords.has(kw)) {
                    canAddKeywords.add(kw);
                }
            });
        });
        return [sortedKeywords, canAddKeywords, canRemoveKeywords];
    }

    private async refreshPossibleKeywords() {
        const [newSortedKeywords, canAddKeywords, canRemoveKeywords] = await this.adjustKeywords(this.sortedKeywords);
        const hasPendingMutations = await this.dataService.countPendingMutations(this.albumId);
        this.ngZone.run(() => {
            this.canAddKeyword = canAddKeywords;
            this.canRemoveKeywords = canRemoveKeywords;
            this.sortedKeywords = newSortedKeywords;
            this.pendingMutationCount = hasPendingMutations;
        });
    }

    private selectedEntriesAsArray() {
        const selectedEntries: string[] = [];
        this.selectedEntries.forEach(id => selectedEntries.push(id));
        return selectedEntries;
    }

    public async selectDate(date: number | undefined): Promise<void> {
        const newDateRange: TimeRange = date === undefined ? undefined : [date, date + 24 * 3600 * 1000];
        if (newDateRange !== this.filteringTimeRange) {
            this.filteringTimeRange = newDateRange;
            this.updateFilterQueryParams();
            await this.refresh();
        }
    }

    public async closeFilterMenu(): Promise<void> {
        await this.menuController.close();
    }

    public async refreshFilters(): Promise<void> {
        if (this.selectedDay === undefined || !Number.isInteger(this.selectedDay)) {
            this.filteringTimeRange = undefined;
        } else {
            this.filteringTimeRange = [this.selectedDay, this.selectedDay + 24 * 3600 * 1000];
        }
        this.updateFilterQueryParams();
        this.lastSelectedIndex = undefined;
        await this.refresh();
        await this.menuController.close();
    }

    public showFilters(): Promise<void> {
        return this.menuController.open('filter').then();
    }

}

interface Shape {
    width: number;
    entry: AlbumEntryData;
    entryIndex: number;
    isVideo: boolean;
    timestamp: number;
}

interface ImageBlock {
    shapes: Shape[];
    scaledImages: Observable<string>[];
    width: number;
    beginTimestamp: number;
    endTimestamp: number;
}

interface ImagesRow {
    kind: 'images';
    blocks: ImageBlock[];
    height: number;
    endTimestamp: number;
}

interface HeaderRow {
    kind: 'timestamp';
    time: Date;
    id: string;
}

type TableRow = ImagesRow | HeaderRow;

enum WaitReason {
    LOAD, STORE
}
