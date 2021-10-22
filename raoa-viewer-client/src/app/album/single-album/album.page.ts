import {Component, ElementRef, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute, Router} from '@angular/router';
import {ServerApiService} from '../../service/server-api.service';
import {AlbumContentGQL, AlbumEntry, SingleAlbumMutateGQL, UserPermissionsGQL} from '../../generated/graphql';
import {HttpClient} from '@angular/common/http';
import {MediaResolverService} from '../service/media-resolver.service';
import {Location} from '@angular/common';
import {IonContent, LoadingController, MenuController, ToastController} from '@ionic/angular';
import {Title} from '@angular/platform-browser';
import {AlbumData, AlbumEntryData} from '../../service/storage.service';
import {DataService} from '../../service/data.service';
import {defer, Observable} from 'rxjs';

type AlbumEntryType =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'id' | 'name' | 'entryUri' | 'targetWidth' | 'targetHeight' | 'created' | 'keywords' | 'contentType'>;

function copyPendingKeywords(pendingKeywords: Map<string, Set<string>>): Map<string, Set<string>> {
    const ret = new Map<string, Set<string>>();
    pendingKeywords.forEach((keywords, entry) => ret.set(entry, new Set<string>(keywords)));
    return ret;
}


@Component({
    selector: 'app-album',
    templateUrl: './album.page.html',
    styleUrls: ['./album.page.css'],
})
export class AlbumPage implements OnInit {


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

    public fnCompetitionId: string;
    private loadingElement: HTMLIonLoadingElement;
    private lastSelectedIndex: number | undefined = undefined;
    private lastScrollPos = 0;

    public albumId: string;
    public title: string;
    public rows: Array<TableRow> = [];
    public days: string[] = [];
    // private keywords = new Set<string>();
    public canAddKeyword = new Set<string>();
    public canRemoveKeywords = new Set<string>();
    public sortedKeywords: string[] = [];
    public maxWidth = 8;
    public filteringKeyword: string;
    public selectionMode = false;

    public selectedEntries = new Set<string>();

    @ViewChild('imageList') private imageListElement: ElementRef<HTMLDivElement>;
    @ViewChild('content') private contentElement: IonContent;
    public elementWidth = 10;

    private sortedEntries: AlbumEntryData[] = [];
    private waitCount = 0;
    public enableSettings = false;
    public daycount = 0;
    public timestamp = 0;
    public canEdit = false;
    public newTag = '';
    // public pendingAddKeywords: Map<string, Set<string>> = new Map<string, Set<string>>();
    // public pendingRemoveKeywords: Map<string, Set<string>> = new Map<string, Set<string>>();
    public filterTimeStep = 60 * 1000;
    public hasPendingMutations = false;

    /*
    private sortKeywords() {
        this.sortedKeywords = [];
        this.keywords.forEach(keyword => this.sortedKeywords.push(keyword));
        this.sortedKeywords.sort((k1, k2) => k1.localeCompare(k2));
    }

     */
    public syncing: boolean = false;

    public async resized() {
        if (this.elementWidth === this.imageListElement.nativeElement.clientWidth) {
            return;
        }
        const timestampBefore = this.timestamp;
        this.elementWidth = this.imageListElement.nativeElement.clientWidth;

        const maxRowHeight = 2 * Math.sqrt((window.innerWidth * window.innerHeight) / 6 / 6);
        const newMaxWidth = Math.min(10, Math.round(this.elementWidth / (Math.min(100 * window.devicePixelRatio, maxRowHeight)) * 4) / 4);
        if (this.maxWidth !== newMaxWidth) {
            this.maxWidth = newMaxWidth;
            await this.calculateRows();
        }
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
            const filteringKeyword = params.get('keyword') || undefined;
            const pos = params.get('pos');
            if (this.albumId) {
                if (this.filteringKeyword !== filteringKeyword) {
                    this.filteringKeyword = filteringKeyword;
                    this.lastSelectedIndex = undefined;
                    await this.refresh();
                }
                if (pos) {
                    const scrollPos: number = Number.parseInt(pos, 10);
                    window.setTimeout(() => {
                        this.contentElement.scrollToPoint(0, scrollPos);
                    }, 500);
                }
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
        this.activatedRoute.queryParamMap.subscribe(async params => {
            const timestamp = params.get('timestamp');
            if (timestamp !== undefined) {
                this.timestamp = Number.parseInt(timestamp, 10);
                await this.refresh();
            }
        });
    }

    private async refresh() {
        await this.enterWait(WaitReason.LOAD);
        try {
            const userPermissions = await this.serverApi.query(this.userPermissionsGQL, {});
            const [album, entries] = await this.dataService.listAlbum(this.albumId);
            const rowCountBefore = this.sortedEntries.length;
            const knownKeywords = new Set<string>();
            entries.forEach(entry => entry.keywords.forEach(kw => knownKeywords.add(kw)));
            const [newSortedKeywords, canAddKeywords, canRemoveKeywords] = await this.adjustKeywords(knownKeywords);
            const hasPendingMutations = await this.dataService.hasPendingMutations(this.albumId);
            this.ngZone.run(() => {
                this.titleService.setTitle(`Album: ${album.title}`);
                this.hasPendingMutations = hasPendingMutations;
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
                this.sortedKeywords = newSortedKeywords;
                this.canAddKeyword = canAddKeywords;
                this.canRemoveKeywords = canRemoveKeywords;
                this.title = album.title;
                let filteredEntries: AlbumEntryData[];
                if (this.filteringKeyword === undefined) {
                    filteredEntries = entries;
                } else {
                    filteredEntries = entries.filter(e => e.keywords.findIndex(k => k === this.filteringKeyword) >= 0);
                }
                if (this.filterTimeStep > 0) {
                    let lastTimestamp = -1;
                    this.sortedEntries = filteredEntries.filter(e => {
                        const timestamp: number = e.created;
                        const t = Math.trunc(timestamp / this.filterTimeStep);
                        if (lastTimestamp !== t) {
                            lastTimestamp = t;
                            return true;
                        }
                        return false;
                    });
                } else {
                    this.sortedEntries = filteredEntries;
                }
                this.fnCompetitionId = album.fnchAlbumId;
                this.enableSettings = userPermissions.currentUser.canManageUsers;
                this.canEdit = userPermissions.currentUser.canEdit;

            });
            await this.calculateRows();
            if (rowCountBefore !== this.sortedEntries.length) {
                this.scrollToTimestamp(this.timestamp);
            }

        } finally {
            await this.leaveWait();
        }
    }

    public scrollToTimestamp(timestamp: number) {
        this.timestamp = timestamp;
        const foundIndices = this.findIndizesOf(this.rows, timestamp);
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

    private async adjustKeywords(knownKeywords: Set<string>): Promise<[string[], Set<string>, Set<string>]> {
        const sortedKeywords: string[] = [];
        knownKeywords.forEach(keyword => sortedKeywords.push(keyword));
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


    async openDayList($event: MouseEvent) {
        await this.menuController.open('days');
    }

    async toggleEditor($event: MouseEvent) {
        await this.menuController.toggle('tags');
    }

    async scrollTo(id: string) {
        const y = document.getElementById(id).offsetTop;
        await this.contentElement.scrollToPoint(0, y);
        await this.menuController.close();
    }

    async filter(keyword: string) {
        if (this.filteringKeyword !== undefined && this.filteringKeyword === keyword) {
            this.filteringKeyword = undefined;
        } else {
            this.filteringKeyword = keyword;
        }
        this.setParam('keyword', this.filteringKeyword);
        this.lastSelectedIndex = undefined;
        await this.refresh();
        await this.menuController.close();
    }

    public createEntryLink(shape: Shape): (string | object)[] {
        return ['/album', this.albumId, 'media', shape.entry.albumEntryId];
    }

    queryParams() {
        if (this.filteringKeyword !== undefined) {
            return {keyword: this.filteringKeyword};
        }
        return {};
    }

    public async imageClicked(blockPart: ImageBlock, shape: Shape, $event: MouseEvent) {
        if (this.selectionMode) {
            const shiftKey = $event.shiftKey;
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
            await this.router.navigate(this.createEntryLink(shape), {queryParams: this.queryParams()});
        }
    }

    private async refreshPossibleKeywords() {
        const [newSortedKeywords, canAddKeywords, canRemoveKeywords] = await this.adjustKeywords(new Set(this.sortedKeywords));
        const hasPendingMutations = await this.dataService.hasPendingMutations(this.albumId);
        this.ngZone.run(() => {
            this.canAddKeyword = canAddKeywords;
            this.canRemoveKeywords = canRemoveKeywords;
            this.sortedKeywords = newSortedKeywords;
            this.hasPendingMutations = hasPendingMutations;
        });
    }

    async tagAdded($event: any) {
        const newKeyword = this.newTag;
        this.newTag = '';
        await this.addTag(newKeyword);
    }

    public selectionCanAdd(keyword: string): boolean {
        return this.canAddKeyword.has(keyword);
    }

    private keywordsOfEntry(entry: AlbumEntryData): Iterable<string> {
        return entry.keywords;
    }

    public selectionCanRemove(keyword: string): boolean {
        return this.canRemoveKeywords.has(keyword);
    }

    async addTag(keyword: string) {
        await this.dataService.addKeyword(this.albumId, this.selectedEntriesAsArray(), [keyword]);
        await this.refreshPossibleKeywords();
    }


    private selectedEntriesAsArray() {
        const selectedEntries: string[] = [];
        this.selectedEntries.forEach(id => selectedEntries.push(id));
        return selectedEntries;
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
        await this.refreshPossibleKeywords();
    }

    selectionModeChanged() {
        if (!this.selectionMode) {
            this.selectedEntries.clear();
        }
    }

    async refreshPendingMutations() {
        const newValue = await this.dataService.hasPendingMutations(this.albumId);
        this.ngZone.run(() => {
            this.hasPendingMutations = newValue;
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
        await this.refresh();
    }

    private findIndizesOf(rows: Array<TableRow>, timestamp: number): [number, number, number] | undefined {
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

    public isResolution(res: number): boolean {
        return this.filterTimeStep === res;
    }

    public async syncAlbum(): Promise<void> {
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
