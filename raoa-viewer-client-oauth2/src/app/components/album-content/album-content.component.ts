// import {FixedSizeVirtualScrollStrategy, VIRTUAL_SCROLL_STRATEGY} from '@angular/cdk/scrolling';
import {ChangeDetectorRef, Component, Inject, OnInit} from '@angular/core';
import {ActivatedRoute, ParamMap, Router} from '@angular/router';
// import {ResizedEvent} from 'angular-resize-event';
import {DomSanitizer} from '@angular/platform-browser';
import {MAT_DIALOG_DATA, MatDialog, MatDialogRef} from '@angular/material';
import {ServerApiService} from '../../services/server-api.service';
import {AlbumContent, AllAlbums, Maybe} from '../../generated/graphql';
import {AppConfigService} from '../../services/app-config.service';
import {ResizedEvent} from 'angular-resize-event';
import {MediaMatcher} from '@angular/cdk/layout';


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
  albumId: string;
  public startSync: () => void;
  public syncRunning: boolean;
  public progressBarMode: 'determinate' | 'indeterminate' | 'buffer' | 'query' = 'indeterminate';
  public progressBarValue: number;
  public mobileQuery: MediaQueryList;
  public mobileQueryListener: () => void;
  public albums: Maybe<AllAlbums.ListAlbums>[] = [];
  private idToken: string;

  constructor(private route: ActivatedRoute,
              private router: Router,
              private sanitizer: DomSanitizer,
              private dialog: MatDialog,
              private serverApi: ServerApiService,
              private configApi: AppConfigService,
              changeDetectorRef: ChangeDetectorRef, media: MediaMatcher
  ) {
    let size = 1600;
    const scales = [];
    while (size > 50) {
      scales.push(size);
      size = Math.round(size * 0.5);
    }
    this.scales = scales.reverse();
    this.mobileQuery = media.matchMedia('(max-width: 600px)');
    this.mobileQueryListener = () => changeDetectorRef.detectChanges();
    this.mobileQuery.addListener(this.mobileQueryListener);
  }

  ngOnInit() {
    // this.configApi.getAuthService().then(authService => authService.authState.subscribe(user => this.idToken = user.idToken));
    this.serverApi.listAllAlbums().then(data => {
      this.albums = data;
    });
    this.route.paramMap.subscribe((params: ParamMap) => {
      this.albumId = params.get('id');
      this.serverApi.listAlbumContent(this.albumId).then(result => {
        const albumById: AlbumContent.AlbumById = result.albumById;
        this.sortedEntries = albumById.entries.filter(e => e.created != null)
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
        this.title = albumById.name;
        this.startSync = () => {
          this.syncRunning = true;
          this.progressBarMode = 'indeterminate';
          caches.open('images').then(c => {
            const remainingEntries = this.sortedEntries.map(e => e.entryUri + '/thumbnail');
            const countDivisor = this.sortedEntries.length / 100;
            let currentNumber = 0;
            this.progressBarValue = 0;
            this.progressBarMode = 'determinate';
            const componentThis = this;
            const firstEntry = remainingEntries.pop();
            if (firstEntry !== undefined) {
              fetch(firstEntry, 5);
            } else {
              this.syncRunning = false;
            }
            for (let i = 0; i < 3 && remainingEntries.length > 0; i++) {
              fetchNext();
            }

            function fetch(url: string, repeat: number) {
              c.match(url)
                .then(existingEntry => existingEntry == null ? c.add(url) : Promise.resolve())
                .then(fetchNext)
                .catch(error => {
                  console.log('Error fetching ' + url);
                  console.log(error);
                  if (repeat > 0) {
                    fetch(url, repeat - 1);
                  } else {
                    componentThis.syncRunning = false;
                  }
                })
              ;
            }

            function fetchNext() {
              currentNumber += 1;
              const nextEntry = remainingEntries.pop();
              if (nextEntry === undefined) {
                componentThis.syncRunning = false;
              } else {
                componentThis.progressBarValue = currentNumber / countDivisor;
                fetch(nextEntry, 5);
              }
            }
          });
        };
        this.loading = false;
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

  zoomIn(): void {
    this.minWidth *= 0.7;
    this.redistributeEntries();
  }

  createUrl(row: TableRow, shape: Shape) {
    // const maxLength = this.findScale(Math.max(shape.width, row.height)); access_token
    return this.trustUrl(shape.entry.entryUri + '/thumbnail');
  }

  findScale(maxLength: number) {
    for (length of this.scales) {
      if (maxLength < length) {
        return length;
      }
    }
    return 1600;
  }

  zoomOut() {
    this.minWidth /= 0.7;
    this.redistributeEntries();
  }

  public downloadZip() {
    this.serverApi.getAlbumZipUri(this.albumId).then(uri => window.location.href = uri);
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

  private redistributeEntries() {
    this.resultRows = [];
    let currentRowContent: AlbumEntry[] = [];
    for (let index = 0; index < this.sortedEntries.length; index++) {
      const entry = this.sortedEntries[index];
      if (entry.targetWidth == null || entry.targetHeight == null) {
        console.log('Invalid entry:');
        console.log(entry);
        continue;
      }
      currentRowContent.push(entry);
      const currentWidth = currentRowContent.map(e => e.targetWidth / e.targetHeight).reduce((sum, current) => sum + current, 0);
      if (currentWidth >= this.minWidth) {
        this.resultRows.push(this.createItems(index, currentRowContent, currentWidth));
        currentRowContent = [];
      }
      if (currentRowContent.length > 50) {
        console.log('Invalid row');
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
    @Inject(MAT_DIALOG_DATA) public data: DialogData,
    private configApi: AppConfigService) {
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
    window.open(entryUri);
  }

  createImageUrl(currentIndex: number) {
    return this.data.sortedEntries[currentIndex].entryUri + '/thumbnail';
  }
}
