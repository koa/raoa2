import {Component, ElementRef, OnInit} from '@angular/core';
import {ActivatedRoute, ParamMap, Router} from '@angular/router';
import {Apollo} from 'apollo-angular';
import gql from 'graphql-tag';

interface QueryResult {
  albumById: AlbumById;
}

interface AlbumById {
  entries: AlbumEntry[];
}

interface AlbumEntry {
  id: string;
  targetWidth: number;
  targetHeight: number;
  created: string;
}

interface Shape {
  width: number;
  entry: AlbumEntry;
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
              private element: ElementRef) {
  }

    ngOnInit() {
      this.route.paramMap.subscribe((params: ParamMap) => {
        console.log('data: ' + params.get('id'));
          return this.apollo.watchQuery({
              query: gql`query AlbumContent($albumId: ID) {albumById(id: $albumId){
                  entries{
                      id, targetWidth, targetHeight, created
                  }
              }
              }
              `, variables: {albumId: params.get('id')}
          }).valueChanges.subscribe(result => {
            console.log('Result: ' + result);
            this.loading = result.loading;
            this.error = result.errors;
            const qr: QueryResult = result.data as QueryResult;
            if (!this.loading && !this.error && qr != null) {
              const minWidth = 4;
              this.resultRows = [];
              const sortedEntries: AlbumEntry[] = qr.albumById.entries.sort();
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
                        entry
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

  resize(event: UIEvent) {
    const targetWidth: number = event.target.innerWidth;
    this.width = targetWidth;
    this.resultRows = this.resultRows.map(currentRowContent => {
      const currentWidth = currentRowContent.shapes.map(e => e.entry).map(e => e.targetWidth / e.targetHeight).reduce((sum, current) => sum + current, 0);
      const rowHeight = this.width / currentWidth;
      const shapes: Shape[] =
        currentRowContent.shapes.map(e => e.entry).map(e => {
          return {
            width: (e.targetWidth / e.targetHeight * rowHeight),
            entry: e
          };
        });
      return {height: rowHeight, shapes};
    });
  }
}
