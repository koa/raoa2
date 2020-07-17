// import {FixedSizeVirtualScrollStrategy, VIRTUAL_SCROLL_STRATEGY} from '@angular/cdk/scrolling';
import {ChangeDetectorRef, Component, Inject, NgZone, OnInit, ViewChild} from '@angular/core';
import {ActivatedRoute, ParamMap, Router} from '@angular/router';
// import {ResizedEvent} from 'angular-resize-event';
import {DomSanitizer} from '@angular/platform-browser';
import {ServerApiService} from '../../services/server-api.service';
import {
  AlbumContent,
  AlbumContentGQL,
  AlbumContentZipGQL,
  AlbumEntryDetail,
  AlbumEntryDetailGQL,
  AllAlbums,
  AllAlbumsGQL,
  AuthenticationState,
  Maybe
} from '../../generated/graphql';
import {AppConfigService} from '../../services/app-config.service';
import {ResizedEvent} from 'angular-resize-event';
import {MediaMatcher} from '@angular/cdk/layout';
import {SelectionModel} from '@angular/cdk/collections';
import {CdkVirtualScrollViewport} from '@angular/cdk/scrolling';
import {MAT_DIALOG_DATA, MatDialog, MatDialogRef} from '@angular/material/dialog';
import {MatListOption} from '@angular/material/list';

interface AlbumEntry {
  id: string;
  name: string;
  entryUri: string;
  targetWidth: number;
  targetHeight: number;
  created: string;
  keywords: string[];
}

interface Shape {
  width: number;
  entry: AlbumEntry;
  entryIndex: number;
}


interface ImagesRow {
  kind: 'images';
  height: number;
  shapes: Shape[];
}

interface HeaderRow {
  kind: 'timestamp';
  time: string;
}

type TableRow = ImagesRow | HeaderRow;

interface NavigationTarget {
  time: string;
  row: number;
}

interface DialogData {
  currentIndex: number;
  sortedEntries: AlbumEntry[];
  albumId: string;
}


function createImagesRow(rowHeight, shapes: Shape[]): ImagesRow {
  return {height: rowHeight, shapes, kind: 'images'} as ImagesRow;
}


function dayOfDate(dateValue: string): string {
  return new Date(dateValue).toDateString();
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
  filteredEntries: AlbumEntry[];
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
  public authenticationState: AuthenticationState;
  public AUTHENTICATED: AuthenticationState = AuthenticationState.Authenticated;
  public UNKNOWN: AuthenticationState = AuthenticationState.Unknown;
  public canManageUsers: Maybe<boolean> = false;
  private albumSearchPattern = '';
  private availableAlbums: Maybe<AllAlbums.ListAlbums>[] = [];
  public availableKeywords: Set<string> = new Set();
  public filteringKeywords: Set<string> = new Set();
  public availableDays: string[] = [];
  public filteringDays: Set<string> = new Set();
  public availableNavigationTargets: NavigationTarget[] = [];
  @ViewChild(CdkVirtualScrollViewport) viewPort: CdkVirtualScrollViewport;

  constructor(private route: ActivatedRoute,
              private router: Router,
              private sanitizer: DomSanitizer,
              private dialog: MatDialog,
              private serverApi: ServerApiService,
              private configApi: AppConfigService,
              private albumListGQL: AllAlbumsGQL,
              private albumContentQGL: AlbumContentGQL,
              private albumContentZipGQL: AlbumContentZipGQL,
              private changeDetectorRef: ChangeDetectorRef,
              private media: MediaMatcher,
              private ngZone: NgZone
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
    this.loading = true;
    this.refreshAlbumList();
    this.route.paramMap.subscribe((params: ParamMap) => {
      this.availableKeywords.clear();
      this.filteringKeywords.clear();
      this.loading = true;
      this.error = undefined;
      this.title = '';
      this.albumId = params.get('id');
      this.serverApi
        .query(this.albumContentQGL, {albumId: this.albumId})
        .then(result => {
          const albumById: AlbumContent.AlbumById = result.albumById;
          this.ngZone.run(() => {
            this.availableKeywords.clear();
            const availableDays: Set<string> = new Set();
            albumById.entries.forEach(e => {
              availableDays.add(dayOfDate(e.created));
              e.keywords.forEach(k => this.availableKeywords.add(k));
            });
            this.availableDays = [];
            if (availableDays.size > 1) {
              const days: string[] = [];
              availableDays.forEach(v => days.push(v));
              days.sort((d1, d2) => new Date(d1).getTime() - new Date(d2).getTime())
                .forEach(day => this.availableDays.push(day));
            }
          });
          this.sortedEntries = albumById.entries.filter(e => e.created != null)
            .map(e => {
              return {
                name: e.name,
                entryUri: this.fixUrl(e.entryUri),
                targetHeight: e.targetHeight,
                targetWidth: e.targetWidth,
                created: e.created,
                id: e.id,
                keywords: e.keywords
              };
            })
            .sort((e1, e2) => e1.created.localeCompare(e2.created));
          this.redistributeEntries();
          this.title = albumById.name;
          this.loading = false;
        })
        .catch(error => {
          this.loading = false;
          this.error = error;
        });
    });
  }

  logout() {
    this.configApi.logout();
    this.refreshAlbumList();
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
        currentIndex: entryIndex,
        sortedEntries: this.filteredEntries,
        albumId: this.albumId
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

  createUrl(row: ImagesRow, shape: Shape) {
    return this.trustUrl(this.createUrlString(row, shape));
  }

  imagesOf(row: ImagesRow | HeaderRow): Shape[] {
    if (row !== undefined && row.kind === 'images') {
      return row.shapes;
    }
    return [];
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
    this.serverApi.query(this.albumContentZipGQL, {albumId: this.albumId})
      .then(result => result.albumById.zipDownloadUri)
      .then(uri => window.location.href = uri);
  }

  updateDayFilter(selectedOptions: SelectionModel<MatListOption>) {
    this.filteringDays.clear();
    selectedOptions.selected.map(o => o.value)
      .forEach(k => this.filteringDays.add(k));
    this.redistributeEntries();
  }

  private createUrlString(row: ImagesRow, shape: Shape) {
    const maxLength = this.findScale(Math.max(shape.width, row.height));
    return shape.entry.entryUri + '/thumbnail?maxLength=' + maxLength;
  }

  updateLabelFilter(selectedOptions: SelectionModel<MatListOption>) {
    this.filteringKeywords.clear();
    selectedOptions.selected.map(o => o.value)
      .forEach(k => this.filteringKeywords.add(k));
    this.redistributeEntries();
  }


  switchUser() {
    this.configApi.selectUserPrompt().then(user => this.ngOnInit());
  }

  updateSearch(value: string) {
    this.albumSearchPattern = value.toLocaleLowerCase();
    this.updateAlbumList();
  }

  private refreshAlbumList() {
    this.serverApi.flushCache();
    this.serverApi.query(this.albumListGQL, {})
      .then(result => {
        if (result == null || result.listAlbums == null) {
          return [];
        } else {
          if (result.authenticationState !== AuthenticationState.Authorized) {
            this.ngZone.run(() => this.router.navigate(['/'], {queryParams: {requestAlbum: this.albumId}}));
            return;
          }
          this.authenticationState = result.authenticationState;

          this.canManageUsers = result.currentUser.canManageUsers;
          return result.listAlbums
            .filter(a => a.albumTime != null)
            .sort((a, b) => -a.albumTime.localeCompare(b.albumTime));
        }
      })
      .then(data => {
        this.availableAlbums = data;
        this.updateAlbumList();
      }).catch(error => {
      this.loading = false;
      this.error = error;
    });
  }

  private updateAlbumList() {
    if (this.albumSearchPattern === '') {
      this.albums = this.availableAlbums;
    } else {
      this.albums = this.availableAlbums.filter(e => e.name.toLocaleLowerCase().includes(this.albumSearchPattern));
    }
  }

  private recalculateComponents() {
    this.resultRows = this.resultRows.map(currentRowContent => {
      switch (currentRowContent.kind) {
        case 'images':
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
          return createImagesRow(rowHeight, shapes);
        default:
          return currentRowContent;
      }
    });
    this.startSync = () => {
      this.syncRunning = true;
      this.progressBarMode = 'indeterminate';
      caches.open('images').then(c => {
        const remainingEntries = this.resultRows
          .flatMap(row =>
            row.kind === 'images'
              ? row.shapes.map(shape => this.createUrlString(row, shape))
              : []);
        const countDivisor = remainingEntries.length / 100;
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
        for (let i = 0; i < 20 && remainingEntries.length > 0; i++) {
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
                setTimeout(() => fetch(url, repeat - 1), 3);
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


  }

  private createItems(index: number, currentRowContent: AlbumEntry[], currentWidth): ImagesRow {
    const startIndex = index - currentRowContent.length + 1;
    const rowHeight = this.width / currentWidth;
    const shapes: Shape[] =
      currentRowContent.map((e, i) => ({
        width: (e.targetWidth / e.targetHeight * rowHeight),
        entry: e,
        uri: (e.entryUri),
        entryIndex: i + startIndex
      }));
    return createImagesRow(rowHeight, shapes);
  }

  private redistributeEntries() {
    this.resultRows = [];
    this.availableNavigationTargets = [];
    let keywordFiltered: AlbumEntry[];
    if (this.filteringKeywords.size > 0) {
      keywordFiltered = this.sortedEntries.filter(e => e.keywords.filter(k => this.filteringKeywords.has(k)).length > 0);
    } else {
      keywordFiltered = this.sortedEntries;
    }
    if (this.filteringDays.size > 0) {
      this.filteredEntries = keywordFiltered.filter(e => this.filteringDays.has(dayOfDate(e.created)));
    } else {
      this.filteredEntries = keywordFiltered;
    }
    let currentHeader: string | null = null;

    let currentRowContent: AlbumEntry[] = [];
    for (let index = 0; index < this.filteredEntries.length; index++) {
      const entry = this.filteredEntries[index];
      const headerString = dayOfDate(entry.created);
      if (currentHeader !== headerString) {
        currentHeader = headerString;
        if (currentRowContent.length > 0) {
          this.resultRows.push(
            this.createItems(index,
              currentRowContent,
              currentRowContent.map(e => e.targetWidth / e.targetHeight).reduce((sum, current) => sum + current, 0)));
          currentRowContent = [];
        }
        this.availableNavigationTargets.push({time: entry.created, row: this.resultRows.length});
        this.resultRows.push({time: entry.created, kind: 'timestamp'});
      }
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
        this.filteredEntries.length - 1,
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
  public showDetails = true;
  public imageProperties: Maybe<AlbumEntryDetail.AlbumEntry>;

  constructor(
    public dialogRef: MatDialogRef<ShowImageDialogComponent>,
    @Inject(MAT_DIALOG_DATA) public data: DialogData,
    private serverApi: ServerApiService,
    private albumEntryDetailGQL: AlbumEntryDetailGQL,
    private ngZone: NgZone
  ) {
    this.currentIndex = data.currentIndex;
    let hackNavi: any;
    hackNavi = window.navigator;
    this.supportShare = hackNavi.share !== undefined;
    this.loadImageProperties();
  }

  loadImageProperties() {
    const data = this.data;
    const albumId = data.albumId;
    const entryId = data.sortedEntries[this.currentIndex].id;
    this.serverApi.query(this.albumEntryDetailGQL, {albumId, entryId})
      .then(props => this.ngZone.run(() => {
        return this.imageProperties = props.albumById.albumEntry;
      }));
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
      this.loadImageProperties();
    }
  }

  right() {
    if (this.currentIndex < this.data.sortedEntries.length - 1) {
      this.currentIndex += 1;
      this.loadImageProperties();
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


  createImageUrl(currentIndex: number) {
    return this.data.sortedEntries[currentIndex].entryUri + '/thumbnail';
  }

  createDownloadUrl(currentIndex: number) {
    return this.data.sortedEntries[currentIndex].entryUri + '/original';
  }

  toggleInfo() {
    this.showDetails = !this.showDetails;
  }
}
