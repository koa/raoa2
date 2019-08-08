// import {FixedSizeVirtualScrollStrategy, VIRTUAL_SCROLL_STRATEGY} from '@angular/cdk/scrolling';
import {Component, Inject, OnInit} from '@angular/core';
import {ActivatedRoute, ParamMap, Router} from '@angular/router';
import {Apollo} from 'apollo-angular';
import gql from 'graphql-tag';
import {ResizedEvent} from 'angular-resize-event';
import {DomSanitizer} from '@angular/platform-browser';
import {MAT_DIALOG_DATA, MatDialog, MatDialogRef} from '@angular/material';


interface QueryResult {
  albumById: AlbumById;
}

interface AlbumById {
  name: string;
  entries: AlbumEntry[];
}

interface AlbumEntry {
  id: string;
  name: string;
  entryUri: string;
  targetWidth: number;
  targetHeight: number;
  created: string;
}

interface Shape {
  width: number;
  entry: AlbumEntry;
  entryIndex: number;
}

interface TableRow {
  height: number;
  shapes: Shape[];
}

interface DialogData {
  currentIndex: number;
  sortedEntries: AlbumEntry[];
}


@Component({
  selector: 'app-album-content',
  templateUrl: './album-content.component.html',
  styleUrls: ['./album-content.component.css'],
  // changeDetection: ChangeDetectionStrategy.OnPush
})
export class AlbumContentComponent implements OnInit {
  resultRows: TableRow[];
  loading = true;
  error: any;
  width = 100;
  sortedEntries: AlbumEntry[];
  minWidth = 5;
  title: string;
  scales: number[];

  constructor(private route: ActivatedRoute,
              private router: Router,
              private apollo: Apollo,
              private sanitizer: DomSanitizer,
              private dialog: MatDialog) {
    let size = 1600;
    const scales = [];
    while (size > 50) {
      scales.push(size);
      size = Math.round(size * 0.5);
    }
    this.scales = scales.reverse();
  }

    ngOnInit() {
      this.route.paramMap.subscribe((params: ParamMap) => {
        const albumId = params.get('id');
          return this.apollo.watchQuery({
              query: gql`query AlbumContent($albumId: ID) {albumById(id: $albumId){
                  name
                  entries{
                      id, name, entryUri, targetWidth, targetHeight, created
                  }
              }
              }
              `, variables: {albumId}
          }).valueChanges.subscribe(result => {
            this.loading = result.loading;
            this.error = result.errors;
            const qr: QueryResult = result.data as QueryResult;
            if (!this.loading && !this.error && qr != null) {
              this.title = qr.albumById.name;
              this.resultRows = [];
              this.sortedEntries = qr.albumById.entries
                .filter(e => e.created != null)
                .map(e => {
                  return {
                    name: e.name,
                    entryUri: this.fixUrl(e.entryUri),
                    targetHeight: e.targetHeight,
                    targetWidth: e.targetWidth,
                    created: e.created,
                    id: e.id
                  };
                })
                .sort((e1, e2) => e1.created.localeCompare(e2.created));
              this.redistributeEntries();
            }
          });
      });
    }

  resized(event: ResizedEvent) {
    this.width = event.newWidth;
    this.recalculateComponents();
  }


  openImage(entryIndex: number) {
    this.dialog.open(ShowImageDialogComponent, {
      width: '100vw',
      height: '100vh',
      maxWidth: '100vw',
      maxHeight: '100vh',
      hasBackdrop: true,
      data: {
        currentIndex: entryIndex, sortedEntries:
        this.sortedEntries
      }
    })
    ;
  }

  trustUrl(urlString: string) {
    return this.sanitizer.bypassSecurityTrustUrl(urlString);
  }

  fixUrl(urlString: string) {
    const url: URL = new URL(urlString);
    url.protocol = window.location.protocol;
    //  url.port = window.location.port;
    return url.href;
  }

  zoomIn() {
    this.minWidth *= 0.7;
    this.redistributeEntries();
  }

  createUrl(row: TableRow, shape: Shape) {
    const maxLength = this.findScale(Math.max(shape.width, row.height));
    return this.trustUrl(shape.entry.entryUri + '/thumbnail?maxLength=' + maxLength);
  }

  private recalculateComponents() {
    this.resultRows = this.resultRows.map(currentRowContent => {
      const currentWidth = currentRowContent.shapes
        .map(e => e.entry)
        .map(e => e.targetWidth / e.targetHeight)
        .reduce((sum, current) => sum + current, 0);
      const rowHeight = this.width / currentWidth;
      const shapes: Shape[] =
        currentRowContent.shapes.map(e => ({
          width: e.entry.targetWidth / e.entry.targetHeight * rowHeight,
          entry: e.entry,
          entryIndex: e.entryIndex,
        }));

      return {height: rowHeight, shapes};
    });
  }

  findScale(maxLength: number) {
    for (length of this.scales) {
      if (maxLength < length) {
        return length;
      }
    }
    return 1600;
  }

  private createItems(index: number, currentRowContent: AlbumEntry[], currentWidth) {
    const startIndex = index - currentRowContent.length + 1;
    const rowHeight = this.width / currentWidth;
    const shapes: Shape[] =
      currentRowContent.map((e, i) => ({
        width: (e.targetWidth / e.targetHeight * rowHeight),
        entry: e,
        uri: (e.entryUri),
        entryIndex: i + startIndex
      }));
    return {height: rowHeight, shapes};
  }

  zoomOut() {
    this.minWidth /= 0.7;
    this.redistributeEntries();
  }

  private redistributeEntries() {
    this.resultRows = [];
    let currentRowContent: AlbumEntry[] = [];
    for (let index = 0; index < this.sortedEntries.length; index++) {
      const entry = this.sortedEntries[index];
      currentRowContent.push(entry);
      const currentWidth = currentRowContent.map(e => e.targetWidth / e.targetHeight).reduce((sum, current) => sum + current, 0);
      if (currentWidth >= this.minWidth) {
        this.resultRows.push(this.createItems(index, currentRowContent, currentWidth));
        currentRowContent = [];
      }
    }
    if (currentRowContent.length > 0) {
      this.resultRows.push(this.createItems(
        this.sortedEntries.length - 1,
        currentRowContent,
        currentRowContent.map(e => e.targetWidth / e.targetHeight).reduce((sum, current) => sum + current, 0))
      );
    }
    this.recalculateComponents();
  }
}

@Component({
  selector: 'app-show-image-dialog',
  templateUrl: 'show-image-dialog.html',
  styleUrls: ['./show-image-dialog.css'],
})
export class ShowImageDialogComponent {
  public currentIndex = 0;
  public supportShare: boolean;

  constructor(
    public dialogRef: MatDialogRef<ShowImageDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: DialogData) {
    this.currentIndex = data.currentIndex;
    let hackNavi: any;
    hackNavi = window.navigator;
    this.supportShare = hackNavi.share !== undefined;
  }

  onNoClick(): void {
    this.dialogRef.close();
  }

  close() {
    this.dialogRef.close();
  }

  left() {
    if (this.currentIndex > 0) {
      this.currentIndex -= 1;
    }
  }

  right() {
    if (this.currentIndex < this.data.sortedEntries.length - 1) {
      this.currentIndex += 1;
    }
  }

  share() {
    let hackNavi: any;
    hackNavi = window.navigator;
    if (hackNavi.share) {
      hackNavi.share({
        title: this.data.sortedEntries[this.currentIndex].name,
        text: 'Shared Photo of RAoA',
        url: this.data.sortedEntries[this.currentIndex].entryUri + '/original'
      });
    } else {
      console.log('Share not supported by this browser');
    }
  }

  download() {

    const entry = this.data.sortedEntries[this.currentIndex];
    const entryUri = entry.entryUri + '/original';
    const filename = entry.name;

    fetch(entryUri).then(r => ({
      filename,
      data: r.blob()
    })).then(res => {
      console.log('start download:', res);
      const url = window.URL.createObjectURL(res.data);
      const a = document.createElement('a');
      document.body.appendChild(a);
      a.setAttribute('style', 'display: none');
      a.href = url;
      a.download = res.filename;
      a.click();
      window.URL.revokeObjectURL(url);
      a.remove(); // remove the element
    });
  }
}
