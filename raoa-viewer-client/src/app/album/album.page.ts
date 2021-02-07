import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {ServerApiService} from '../service/server-api.service';
import {AlbumContentGQL, AlbumEntry} from '../generated/graphql';

type AlbumEntryType =
    { __typename?: 'AlbumEntry' }
    & Pick<AlbumEntry, 'id' | 'name' | 'entryUri' | 'targetWidth' | 'targetHeight' | 'created' | 'keywords'>;

@Component({
    selector: 'app-album',
    templateUrl: './album.page.html',
    styleUrls: ['./album.page.css'],
})
export class AlbumPage implements OnInit {
    public albumId: string;
    public title: string;
    public rows: Array<TableRow> = [];


    constructor(private activatedRoute: ActivatedRoute, private serverApi: ServerApiService,
                private albumContentGQL: AlbumContentGQL,
                private ngZone: NgZone) {
    }

    ngOnInit() {
        this.albumId = this.activatedRoute.snapshot.paramMap.get('id');
        this.serverApi.query(this.albumContentGQL, {albumId: this.albumId})
            .then(content => {

                this.ngZone.run(() => {
                    this.title = content.albumById.name;
                    this.rows = [];
                    const maxWidth = 4;
                    const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
                    let currentImageDate: number;
                    let index = 0;
                    let currentRow: Shape[] = [];
                    let currentRowWidth = 0;
                    const flushRow = () => {
                        if (currentRow.length > 0) {
                            this.rows.push({
                                kind: 'images',
                                shapes: currentRow,
                                width: currentRowWidth
                            });
                        }
                        currentRowWidth = 0;
                        currentRow = [];
                    };
                    const appender = (shape: Shape, date: number) => {
                        if (currentImageDate === undefined || currentImageDate !== date) {
                            flushRow();
                            this.rows.push({kind: 'timestamp', time: new Date(date)});
                        }
                        currentImageDate = date;
                        const totalWidth = currentRowWidth;
                        if (totalWidth + shape.width > maxWidth) {
                            flushRow();
                        }
                        currentRow.push(shape);
                        currentRowWidth += shape.width;
                    };

                    content.albumById.entries
                        .slice()
                        .sort((e1, e2) => {
                            const c1 = e1?.created;
                            const c2 = e2?.created;
                            return c1 === c2 ? 0 : c1 === null || c1 === undefined ? 1 : c1.localeCompare(c2);
                        })
                        .forEach(entry => {
                            const timestamp: number = Date.parse(entry.created);

                            const date1 = new Date(timestamp);
                            date1.setHours(0, 0, 0, 0);
                            const imageDate = date1.valueOf();
                            const imageWidth = entry.targetWidth / entry.targetHeight;
                            const imageShape: Shape = {
                                width: imageWidth,
                                entry,
                                entryIndex: index++
                            };
                            appender(imageShape, imageDate);
                        });
                    flushRow();
                });
            })
        ;
    }

}

function dateFromISO8601(isostr) {
    const parts = isostr.match(/\d+/g);
    return new Date(parts[0], parts[1] - 1, parts[2], parts[3], parts[4], parts[5]);
}

interface Shape {
    width: number;
    entry: AlbumEntryType;
    entryIndex: number;
}

interface ImagesRow {
    kind: 'images';
    shapes: Shape[];
    width: number;
}

interface HeaderRow {
    kind: 'timestamp';
    time: Date;
}

type TableRow = ImagesRow | HeaderRow;

