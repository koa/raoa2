// import {FixedSizeVirtualScrollStrategy, VIRTUAL_SCROLL_STRATEGY} from '@angular/cdk/scrolling';
import {Component, OnInit} from '@angular/core';
import {ActivatedRoute, ParamMap, Router} from '@angular/router';
import {Apollo} from 'apollo-angular';
import gql from 'graphql-tag';
import {ResizedEvent} from 'angular-resize-event';
import {DomSanitizer} from '@angular/platform-browser';
import {Lightbox} from 'ngx-lightbox';


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
  thumbnailUri: string;
  targetWidth: number;
  targetHeight: number;
  created: string;
}

interface Shape {
  width: number;
  entry: AlbumEntry;
  entryIndex: number;
  thumbnail: string;
}

interface TableRow {
  height: number;
  shapes: Shape[];
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

  constructor(private route: ActivatedRoute,
              private router: Router,
              private apollo: Apollo,
              private sanitizer: DomSanitizer,
              private lightbox: Lightbox) {
  }

    ngOnInit() {
      this.route.paramMap.subscribe((params: ParamMap) => {
        const albumId = params.get('id');
          return this.apollo.watchQuery({
              query: gql`query AlbumContent($albumId: ID) {albumById(id: $albumId){
                  name
                  entries{
                      id, name, thumbnailUri, targetWidth, targetHeight, created
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
              this.sortedEntries = qr.albumById.entries.sort((e1, e2) => e1.created.localeCompare(e2.created));
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
    const album = this.sortedEntries.map(e => ({
      src: e.thumbnailUri,
      caption: e.name,
      thumb: e.thumbnailUri,
    }));
    this.lightbox.open(album, entryIndex, {disableScrolling: true, centerVertically: true});
  }

  private redistributeEntries() {
    let currentRowContent: AlbumEntry[] = [];
    for (let index = 0; index < this.sortedEntries.length; index++) {
      const entry = this.sortedEntries[index];
      currentRowContent.push(entry);
      const currentWidth = currentRowContent.map(e => e.targetWidth / e.targetHeight).reduce((sum, current) => sum + current, 0);
      if (currentWidth >= this.minWidth) {
        const startIndex = index - currentRowContent.length + 1;
        const rowHeight = this.width / currentWidth;
        const shapes: Shape[] =
          currentRowContent.map((e, i) => ({
            width: (e.targetWidth / e.targetHeight * rowHeight),
            entry: e,
            thumbnail: (e.thumbnailUri),
            entryIndex: i + startIndex
          }));
        const items = {height: rowHeight, shapes};
        // console.log('Row: ' + items);
        this.resultRows.push(items);
        currentRowContent = [];
      }
    }
    this.recalculateComponents();
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
          thumbnail: e.thumbnail
        }));

      return {height: rowHeight, shapes};
    });
  }
}
