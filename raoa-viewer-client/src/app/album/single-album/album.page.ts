import {Component, ElementRef, NgZone, OnDestroy, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {ServerApiService} from '../../service/server-api.service';
import {AlbumContentGQL, SingleAlbumMutateGQL} from '../../generated/graphql';
import {HttpClient} from '@angular/common/http';
import {MediaResolverService} from '../service/media-resolver.service';
import {Location} from '@angular/common';
import {GestureController, IonContent, LoadingController, MenuController, PopoverController} from '@ionic/angular';
import {Title} from '@angular/platform-browser';
import {AlbumEntryData} from '../../service/storage.service';
import {createFilter, DataService, filterTimeResolution} from '../../service/data.service';
import {defer, Observable, Subscription} from 'rxjs';
import {
    createFilterQueryParams,
    createMediaPath,
    KeywordCombine,
    parseFilterParams,
    TimeRange,
    updateSearchParams
} from '../show-single-media/show-single-media.component';
import {filter} from 'rxjs/operators';
import {
    SingleAlbumRightPopoverMenuComponent
} from './single-album-right-popover-menu/single-album-right-popover-menu.component';
import {MultiWindowService} from 'ngx-multi-window';


let instanceCounter = 0;

function calcTouchDist(t0: Touch, t1: Touch): number {
    const xDist = t0.screenX - t1.screenX;
    const yDist = t0.screenY - t1.screenY;
    return Math.sqrt(yDist * yDist + xDist * xDist);
}

@Component({
    selector: 'app-album',
    templateUrl: './album.page.html',
    styleUrls: ['./album.page.css'],
})
export class AlbumPage implements OnInit, OnDestroy {
    private scrollToTimerHandler: number;

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
                private popoverController: PopoverController,
                private gestureController: GestureController,
                private multiWindowService: MultiWindowService,
    ) {
        this.filterId = 'filter-' + instanceCounter++;
    }

    @ViewChild('imageList')
    set imageList(imageListElement: ElementRef<HTMLDivElement>) {
        this.imageListElement = imageListElement;
    }

    public allDateCounts = 0;
    public syncEnabled: boolean;
    public fnCompetitionId: string;
    public albumId: string;
    public title: string;
    public rows: Array<TableRow> = [];
    public days: number[] = [];
    public canAddKeyword = new Set<string>();
    public canRemoveKeywords = new Set<string>();
    public sortedKeywords: string[] = [];
    public maxWidth = 8;
    public filteringKeywords = new Set<string>();
    public keywordCombinator: KeywordCombine = 'and';
    public selectedEntries = new Set<string>();
    public elementWidth = 10;
    public enableSettings = false;
    public daycount = 0;
    public timestamp = 0;
    public canEdit = false;
    public newTag = '';
    public filterTimeStep = 60 * 1000;
    public pendingMutationCount = 0;
    public keywordCounters: Map<string, number> = new Map<string, number>();
    public dayCounters = new Map<number, number>();
    public syncing = false;
    public selectedDay: number | undefined;
    public filterId = 'filter';
    public selectionMode = false;
    private imageListElement: ElementRef<HTMLDivElement>;
    @ViewChild('content') private contentElement: IonContent;
    private filteringTimeRange: TimeRange;
    private loadingElement: HTMLIonLoadingElement;
    private lastSelectedIndex: number | undefined = undefined;
    private lastScrollPos = 0;
    private sortedEntries: AlbumEntryData[] = [];
    private waitCount = 0;
    private zoomStartDist = 1;
    private currentZoomState = 1;
    private zoomStartMaxWidth = 1;
    private queryMapSubscribe: Subscription;
    private paramMapSubscribe: Subscription;
    private albumModifiedSubscribe: Subscription;
    public canPresent = false;
    public selectedInDiashow: string[] = [];
    public selectedNotDiashow: string[] = [];

    private currentUpdateTimer: undefined | number = undefined;
    private lastUpdateTime = Date.now();

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

    public ngOnDestroy(): void {
        this.queryMapSubscribe?.unsubscribe();
        this.paramMapSubscribe?.unsubscribe();
        this.albumModifiedSubscribe?.unsubscribe();
        if (this.scrollToTimerHandler !== undefined) {
            window.clearTimeout(this.scrollToTimerHandler);
            this.scrollToTimerHandler = undefined;
        }
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
            const nextPossibleUpdateTime = this.lastUpdateTime + 5000;
            let now: number = Date.now();
            if (now > nextPossibleUpdateTime) {
                this.setParam('timestamp', this.timestamp.toString());
                this.lastUpdateTime = now;
            } else {
                if (this.currentUpdateTimer === undefined) {
                    this.currentUpdateTimer = setTimeout(() => {
                        this.setParam('timestamp', this.timestamp.toString());
                        this.currentUpdateTimer = undefined;
                        this.lastUpdateTime = Date.now();
                    }, nextPossibleUpdateTime - now);
                }
            }
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
        this.queryMapSubscribe = this.activatedRoute.queryParamMap.subscribe(async params => {

            const [filteringKeywords, keywordCombine, filteringTimeRange, timeResolution] = parseFilterParams(params);
            let refreshNeeded = false;
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
            const timestamp = params.get('timestamp');
            if (timestamp !== undefined) {
                this.timestamp = Number.parseInt(timestamp, 10);
                refreshNeeded = true;
            }
            if (refreshNeeded) {
                await this.refresh();
            }

        });
        this.paramMapSubscribe = this.activatedRoute.paramMap.subscribe(async params => {
            const id = params.get('id');
            if (this.albumId !== id) {
                this.albumId = id;
                this.selectedEntries.clear();
                this.lastSelectedIndex = undefined;
                await this.refresh();
            }
        });
        this.albumModifiedSubscribe = this.dataService.albumModified
            .pipe(filter(albumId => this.albumId === albumId))
            .subscribe(modifiedAlbum => {
                this.refresh();
            });
        this.multiWindowService.onWindows().subscribe(windows => {
            let canPresent = false;
            for (const window of windows) {
                if (window.id !== this.multiWindowService.id) {
                    canPresent = true;
                }
            }
            this.ngZone.run(() => this.canPresent = canPresent);
        });

    }

    public scrollToTimestamp(timestamp: number) {
        this.timestamp = timestamp;
        if (this.scrollToTimerHandler !== undefined) {
            window.clearTimeout(this.scrollToTimerHandler);
        }
        const foundIndices = AlbumPage.findIndizesOf(this.rows, timestamp);
        if (foundIndices !== undefined) {
            const rowIndex = foundIndices[0];
            const blockIndex = foundIndices[1];
            // console.log('scroll to ' + rowIndex + ', ' + blockIndex);
            // const shapeIndex = foundIndices[2];
            this.scrollToTimerHandler = window.setTimeout(() => {
                const rowElement: HTMLElement = document.getElementById('row-' + rowIndex);
                if (rowElement === null) {
                    console.log('row not found', timestamp, rowIndex);
                    return;
                }
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


    async filter(keyword: string) {
        if (this.filteringKeywords.has(keyword)) {
            this.filteringKeywords.delete(keyword);
        } else {
            this.filteringKeywords.add(keyword);
        }
        await this.refreshFilters();
    }

    public createEntryLink(shape: Shape): string {
        const filteringKeywords: string[] = [];
        this.filteringKeywords.forEach(kw => filteringKeywords.push(kw));
        return createMediaPath(this.albumId,
            shape.entry.albumEntryId,
            filteringKeywords,
            'and',
            this.filteringTimeRange,
            this.filterTimeStep).toString();
    }


    public async imageClicked(blockPart: ImageBlock, shape: Shape, $event: MouseEvent) {
        const shiftKey = $event.shiftKey;
        const selectionMode = $event.ctrlKey || this.selectionMode;
        if (selectionMode || shiftKey) {
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
                }
            } else {
                if (this.selectedEntries.has(entryId)) {
                    this.selectedEntries.delete(entryId);
                } else {
                    this.selectedEntries.add(entryId);
                }
            }
            this.lastSelectedIndex = selectedIndex;
            await this.refreshPossibleKeywords();
        } else {
            await this.goToAlbumEntry(shape);
        }
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

    public async syncAlbum(): Promise<void> {
        await this.dataService.setSync(this.albumId, !this.syncEnabled);
        await this.refresh();
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
        // await this.menuController.close();
    }

    public showFilters(): Promise<void> {
        return this.menuController.open(this.filterId).then();
    }

    public async openNavigationMenu(): Promise<void> {
        return this.menuController.open('navigation').then();
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
            return Number.parseInt(bestElement.getAttribute('timestamp'), 10);
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
                    entry.keywords.forEach(kw => {
                        if (!knownKeywords.has(kw)) {
                            knownKeywords.set(kw, 0);
                        }
                    });
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
                this.keywordCounters = knownKeywords;
                this.sortedKeywords = newSortedKeywords;
                this.canAddKeyword = canAddKeywords;
                this.canRemoveKeywords = canRemoveKeywords;
                this.title = album.title;
                this.syncEnabled = albumSettings ? albumSettings.syncOffline : false;
                this.sortedEntries = filterTimeResolution(entries, this.filterTimeStep);

                this.fnCompetitionId = album.fnchAlbumId;
                this.enableSettings = userPermissions.canManageUsers;
                this.canEdit = userPermissions.canEdit || userPermissions.canManageUsers;

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
        const checkKeys = new Set<[string, string]>();
        for (const selectedId of this.selectedEntries) {
            checkKeys.add([this.albumId, selectedId]);
        }
        const foundEntries: Set<[string, string]> = await this.dataService.filterInDiashow(checkKeys);
        const foundIds = new Set<string>();
        for (const foundEntry of foundEntries) {
            if (foundEntry[0] === this.albumId) {
                foundIds.add(foundEntry[1]);
            }
        }
        const selectedInDiashow: string[] = [];
        const selectedNotDiashow: string[] = [];
        for (const selectedId of this.selectedEntries) {
            if (foundIds.has(selectedId)) {
                selectedInDiashow.push(selectedId);
            } else {
                selectedNotDiashow.push(selectedId);
            }
        }

        this.ngZone.run(() => {
            this.canAddKeyword = canAddKeywords;
            this.canRemoveKeywords = canRemoveKeywords;
            this.sortedKeywords = newSortedKeywords;
            this.pendingMutationCount = hasPendingMutations;
            this.selectedInDiashow = selectedInDiashow;
            this.selectedNotDiashow = selectedNotDiashow;
        });

    }

    private selectedEntriesAsArray() {
        const selectedEntries: string[] = [];
        this.selectedEntries.forEach(id => selectedEntries.push(id));
        return selectedEntries;
    }

    public async popupMenu(ev: Event): Promise<void> {
        const popover = await this.popoverController.create({
            component: SingleAlbumRightPopoverMenuComponent,
            event: ev,
            componentProps: {
                fnchCompetitionId: this.fnCompetitionId,
                onShowRanking: () => {
                    popover.dismiss();
                },
                selectMode: this.selectionMode,
                toggleSelectMode: () => {
                    popover.dismiss();
                    this.selectionMode = !this.selectionMode;
                }
            },
            translucent: true
        });
        await popover.present();
    }

    onTouchStart(ev: TouchEvent) {
        if (ev.touches.length !== 2) {
            return;
        }

        const data = calcTouchDist(ev.touches[0], ev.touches[1]);
        this.zoomStartDist = data;
        this.zoomStartMaxWidth = this.maxWidth;
        this.currentZoomState = 1;
    }

    async onTouchMove(ev: TouchEvent) {
        if (ev.touches.length !== 2) {
            return;
        }
        const currentDist = calcTouchDist(ev.touches[0], ev.touches[1]);

        const data = currentDist / this.zoomStartDist;
        this.currentZoomState = data;
        const width = Math.round(this.zoomStartMaxWidth / data);
        if (width !== this.maxWidth) {
            const timestampBefore = this.timestamp;
            this.maxWidth = width;
            await this.calculateRows();
            this.scrollToTimestamp(timestampBefore);
        }
    }

    public async appendSelectedToDiashow() {
        for (const selectedId of this.selectedNotDiashow) {
            await this.dataService.appendDiashow(this.albumId, selectedId);
        }
        await this.refreshPossibleKeywords();
    }

    public async removeSelectedFromDiashow() {
        for (const selectedId of this.selectedInDiashow) {
            await this.dataService.removeDiashow(this.albumId, selectedId);
        }
        await this.refreshPossibleKeywords();
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
