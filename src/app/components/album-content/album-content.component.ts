import {Component, OnInit} from '@angular/core';
import {ActivatedRoute, ParamMap, Router} from '@angular/router';
import {Apollo} from 'apollo-angular';
import gql from 'graphql-tag';
import {ResizedEvent} from 'angular-resize-event';
import {DomSanitizer, SafeUrl} from '@angular/platform-browser';

interface QueryResult {
  albumById: AlbumById;
}

interface AlbumById {
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
  thumbnail: SafeUrl;
}

interface TableRow {
  height: number;
  shapes: Shape[];
}


@Component({
  selector: 'app-album-content',
  templateUrl: './album-content.component.html',
  styleUrls: ['./album-content.component.css']
})
export class AlbumContentComponent implements OnInit {
  resultRows: TableRow[];
  loading = true;
  error: any;
  width = 50;

  constructor(private route: ActivatedRoute,
              private router: Router,
              private apollo: Apollo,
              private sanitizer: DomSanitizer) {
  }

    ngOnInit() {
      this.route.paramMap.subscribe((params: ParamMap) => {
        const albumId = params.get('id');
          return this.apollo.watchQuery({
              query: gql`query AlbumContent($albumId: ID) {albumById(id: $albumId){
                  entries{
                      id, name, thumbnailUri, targetWidth, targetHeight, created
                  }
              }
              }
              `, variables: {albumId}
          }).valueChanges.subscribe(result => {
            console.log('Result: ' + result);
            this.loading = result.loading;
            this.error = result.errors;
            const qr: QueryResult = result.data as QueryResult;
            if (!this.loading && !this.error && qr != null) {
              const minWidth = 4;
              this.resultRows = [];
              const sortedEntries: AlbumEntry[] = qr.albumById.entries.sort((e1, e2) => e1.created.localeCompare(e2.created));
              let currentRowContent: AlbumEntry[] = [];

              for (const entry of sortedEntries) {
                currentRowContent.push(entry);
                const currentWidth = currentRowContent.map(e => e.targetWidth / e.targetHeight).reduce((sum, current) => sum + current, 0);
                if (currentWidth >= minWidth) {
                  const rowHeight = this.width / currentWidth;
                  const shapes: Shape[] =
                    currentRowContent.map(e => {
                      return {
                        width: (e.targetWidth / e.targetHeight * rowHeight),
                        entry: e,
                        thumbnail: this.sanitizer.bypassSecurityTrustUrl(e.thumbnailUri)
                      };
                    });
                  const items = {height: rowHeight, shapes};
                  // console.log('Row: ' + items);
                  this.resultRows.push(items);
                  currentRowContent = [];
                }
              }
              // console.log('Row count: ' + this.resultRows);
            }
          });
      });
    }

  resized(event: ResizedEvent) {
    this.width = event.newWidth;
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
          thumbnail: e.thumbnail
        }));
      return {height: rowHeight, shapes};
    });
  }
}
