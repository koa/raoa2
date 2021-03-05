import {Component, ElementRef, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {ServerApiService} from '../../service/server-api.service';
import {AlbumContentGQL, AlbumEntry} from '../../generated/graphql';
import {HttpClient} from '@angular/common/http';
import {MediaResolverService} from '../service/media-resolver.service';
import {AlbumListService} from '../service/album-list.service';
import {Location} from '@angular/common';
import {LoadingController} from '@ionic/angular';

type AlbumEntryType =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'id' | 'name' | 'entryUri' | 'targetWidth' | 'targetHeight' | 'created' | 'keywords'>;

@Component({
    selector: 'app-album',
    templateUrl: './album.page.html',
    styleUrls: ['./album.page.css'],
})
export class AlbumPage implements OnInit {
    private loadingElement: HTMLIonLoadingElement;


    constructor(private activatedRoute: ActivatedRoute, private serverApi: ServerApiService,
                private albumContentGQL: AlbumContentGQL,
                private albumListService: AlbumListService,
                private ngZone: NgZone,
                private http: HttpClient,
                private mediaResolver: MediaResolverService,
                private location: Location,
                private loadingController: LoadingController
    ) {
    }

    public albumId: string;
    public title: string;
    public rows: Array<TableRow> = [];
    public days: string[] = [];
    public maxWidth = 8;

    @ViewChild('imageList') private element: ElementRef;
    public elementWidth = 10;

    private sortedEntries: AlbumEntryType[] = [];
    private waitCount = 0;

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
        this.albumId = this.activatedRoute.snapshot.paramMap.get('id');
        await this.enterWait();
        const result = await this.albumListService.listAlbum(this.albumId);
        await this.leaveWait();
        this.ngZone.run(() => {
            this.title = result.title;
            this.sortedEntries = result.sortedEntries;
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
                currentBlock.push({
                    shapes: currentRow,
                    width: currentRowWidth
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
        const appender = (shape: Shape, date: number) => {
            if (currentImageDate === undefined || currentImageDate !== date) {
                flushBlock();
                this.rows.push({kind: 'timestamp', time: new Date(date)});
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
        await this.leaveWait();
    }

    public loadImage(blockPart: ImageBlock, shape: Shape): string {
        const imgWidthPixels = this.elementWidth / blockPart.width * shape.width;
        const maxLength: number = shape.width < 1 ? imgWidthPixels / shape.width : imgWidthPixels;

        const entryId = shape.entry.id;
        return this.mediaResolver.lookupImage(this.albumId, entryId, maxLength);
    }


    back() {
        this.location.back();
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
}

interface ImagesRow {
    kind: 'images';
    blocks: ImageBlock[];
    height: number;
}

interface HeaderRow {
    kind: 'timestamp';
    time: Date;
}

type TableRow = ImagesRow | HeaderRow;

