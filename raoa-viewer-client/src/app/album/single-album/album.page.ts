import {Component, ElementRef, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {ServerApiService} from '../../service/server-api.service';
import {AlbumContentGQL, AlbumEntry} from '../../generated/graphql';
import {HttpClient} from '@angular/common/http';
import {MediaResolverService} from '../service/media-resolver.service';
import {AlbumListService} from '../service/album-list.service';
import {Location} from '@angular/common';
import {IonContent, LoadingController, MenuController} from '@ionic/angular';
import {FNCH_COMPETITION_ID} from '../../constants';

type AlbumEntryType =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'id' | 'name' | 'entryUri' | 'targetWidth' | 'targetHeight' | 'created' | 'keywords'>;

@Component({
    selector: 'app-album',
    templateUrl: './album.page.html',
    styleUrls: ['./album.page.css'],
})
export class AlbumPage implements OnInit {
    public fnCompetitionId: string;
    private loadingElement: HTMLIonLoadingElement;


    constructor(private activatedRoute: ActivatedRoute, private serverApi: ServerApiService,
                private albumContentGQL: AlbumContentGQL,
                private albumListService: AlbumListService,
                private ngZone: NgZone,
                private http: HttpClient,
                private mediaResolver: MediaResolverService,
                private location: Location,
                private loadingController: LoadingController,
                private menuController: MenuController
    ) {
    }

    public albumId: string;
    public title: string;
    public rows: Array<TableRow> = [];
    public days: string[] = [];
    public keywords: string[] = [];
    public maxWidth = 8;
    public filteringKeyword: string;

    @ViewChild('imageList') private element: ElementRef<HTMLDivElement>;
    @ViewChild('content') private contentElement: IonContent;
    public elementWidth = 10;

    private sortedEntries: AlbumEntryType[] = [];
    private waitCount = 0;
    public enableSettings = false;
    public daycount = 0;
    public timestamp = '';

    public async resized() {
        if (this.elementWidth === this.element.nativeElement.clientWidth) {
            return;
        }
        this.elementWidth = this.element.nativeElement.clientWidth;

        const maxRowHeight = 2 * Math.sqrt((window.innerWidth * window.innerHeight) / 6 / 6);
        const newMaxWidth = Math.min(10, Math.round(this.elementWidth / (Math.min(100 * window.devicePixelRatio, maxRowHeight)) * 4) / 4);
        if (this.maxWidth !== newMaxWidth) {
            this.maxWidth = newMaxWidth;
            await this.calculateRows();
        }
    }

    async onScroll(e: CustomEvent) {
        const detail = e.detail;
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
            this.timestamp = bestElement.getAttribute('timestamp');
        }
        this.setParam('pos', detail.scrollTop);
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

    async enterWait(): Promise<void> {
        const newCount = ++this.waitCount;
        if (newCount === 1 && this.loadingElement === undefined) {
            const element = await this.loadingController.create({message: this.title ? ('Lade ' + this.title + ' ...') : 'Lade Album ...'});
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
        this.activatedRoute.queryParamMap.subscribe(async params => {
            const filteringKeyword = params.get('keyword') || undefined;
            const pos = params.get('pos');
            if (this.albumId) {
                if (this.filteringKeyword !== filteringKeyword) {
                    this.filteringKeyword = filteringKeyword;
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
                await this.refresh();
            } else {
                const result = await this.albumListService.listAlbum(this.albumId);
                const keywords = new Set<string>();
                result.keywords.forEach((count, keyword) => keywords.add(keyword));
                result.sortedEntries.forEach(entry => entry.keywords.forEach(keyword => keywords.add(keyword)));
                this.keywords = [];
                keywords.forEach(keyword => this.keywords.push(keyword));
                this.keywords.sort((k1, k2) => k1.localeCompare(k2));
                console.log('welcome back');
            }
        });
    }

    private async refresh() {
        await this.enterWait();
        const result = await this.albumListService.listAlbum(this.albumId);
        await this.leaveWait();
        this.ngZone.run(() => {
            this.title = result.title;
            if (this.filteringKeyword === undefined) {
                this.sortedEntries = result.sortedEntries;
            } else {
                this.sortedEntries = result.sortedEntries.filter(e => e.keywords.findIndex(k => k === this.filteringKeyword) >= 0);
            }
            this.enableSettings = result.canManageUsers;
            this.fnCompetitionId = result.labels.get(FNCH_COMPETITION_ID);
            this.keywords = [];
            result.keywords.forEach((value, key) => this.keywords.push(key));
            this.keywords.sort((k1, k2) => k1.localeCompare(k2));
            this.calculateRows();
        });
    }

    private async calculateRows() {
        await this.enterWait();
        this.rows = [];
        const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
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
                const title = currentRow[0].entry.created;
                currentBlock.push({
                    shapes: currentRow,
                    width: currentRowWidth,
                    title
                });
                currentBlockLength += 1 / currentRowWidth;
                currentBlockMediaCount += currentRow.length;
            }
            currentRow = [];
            currentRowWidth = 0;
        };
        const flushBlock = () => {
            flushRow();
            if (currentBlock.length > 0) {
                this.rows.push({kind: 'images', blocks: currentBlock, height: currentBlockLength});
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
                this.rows.push({kind: 'timestamp', time: new Date(date), id: date.toString()});
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
        const realKeywords = new Set<string>();
        this.sortedEntries
            .forEach(entry => {
                entry.keywords.forEach(keyword => {
                    realKeywords.add(keyword);
                });
                const timestamp: number = Date.parse(entry.created);
                const date = new Date(timestamp);
                date.setHours(0, 0, 0, 0);
                const imageDate = date.valueOf();
                const imageWidth = entry.targetWidth / entry.targetHeight;
                const imageShape: Shape = {
                    width: imageWidth,
                    entry,
                    entryIndex: index++
                };
                appender(imageShape, imageDate);
            });
        flushBlock();
        this.keywords = [];
        realKeywords.forEach(keyword => this.keywords.push(keyword));
        this.keywords.sort((k1, k2) => k1.localeCompare(k2));
        this.daycount = dayCount;
        await this.leaveWait();
    }

    public loadImage(blockPart: ImageBlock, shape: Shape): string {
        const imgWidthPixels = this.elementWidth / blockPart.width * shape.width;
        const maxLength: number = shape.width < 1 ? imgWidthPixels / shape.width : imgWidthPixels;

        const entryId = shape.entry.id;
        return this.mediaResolver.lookupImage(this.albumId, entryId, maxLength);
    }

    async openDayList($event: MouseEvent) {
        await this.menuController.open('days');
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
        await this.refresh();
        await this.menuController.close();
    }

    public createEntryLink(shape: Shape): (string | object)[] {
        return ['/album', this.albumId, 'media', shape.entry.id];
    }

    queryParams() {
        if (this.filteringKeyword !== undefined) {
            return {keyword: this.filteringKeyword};
        }
        return {};
    }
}

interface Shape {
    width: number;
    entry: AlbumEntryType;
    entryIndex: number;
}

interface ImageBlock {
    shapes: Shape[];
    width: number;
    title: string;
}

interface ImagesRow {
    kind: 'images';
    blocks: ImageBlock[];
    height: number;
}

interface HeaderRow {
    kind: 'timestamp';
    time: Date;
    id: string;
}

type TableRow = ImagesRow | HeaderRow;

