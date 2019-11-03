import {ChangeDetectorRef, Component, OnInit} from '@angular/core';
import {ServerApiService} from '../../services/server-api.service';
import {AllAlbums, AllAlbumsGQL, Maybe} from '../../generated/graphql';
import {MediaMatcher} from '@angular/cdk/layout';

@Component({
  selector: 'app-album-list',
  templateUrl: './album-list.component.html',
  styleUrls: ['./album-list.component.css']
})
export class AlbumListComponent implements OnInit {
  public mobileQuery: MediaQueryList;
  albums: Maybe<AllAlbums.ListAlbums>[] = [];
  private mobileQueryListener: () => void;

  constructor(private serverApi: ServerApiService, private albumListGQL: AllAlbumsGQL,
              changeDetectorRef: ChangeDetectorRef, media: MediaMatcher) {
    this.mobileQuery = media.matchMedia('(max-width: 600px)');
    this.mobileQueryListener = () => changeDetectorRef.detectChanges();
    this.mobileQuery.addListener(this.mobileQueryListener);
  }

  ngOnInit() {
    this.serverApi.query(this.albumListGQL, {})
      .then(result => {
        if (result == null || result.listAlbums == null) {
          return [];
        } else {
          return result.listAlbums
            .filter(a => a.albumTime != null)
            .sort((a, b) => -a.albumTime.localeCompare(b.albumTime));
        }
      })
      .then(data => {
        this.albums = data;
      });
  }

}
