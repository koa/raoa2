import {ChangeDetectorRef, Component, OnInit} from '@angular/core';
import {ServerApiService} from '../../services/server-api.service';
import {AllAlbums, Maybe} from '../../generated/graphql';
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

  constructor(private serverApi: ServerApiService,
              changeDetectorRef: ChangeDetectorRef, media: MediaMatcher) {
    this.mobileQuery = media.matchMedia('(max-width: 600px)');
    this.mobileQueryListener = () => changeDetectorRef.detectChanges();
    this.mobileQuery.addListener(this.mobileQueryListener);
  }

  ngOnInit() {
    return this.serverApi.listAllAlbums().then(data => {
      this.albums = data;
    }).catch(error =>
      console.log(error));
    /*this.authService.authState.subscribe(user => {
      }
    );*/
  }

}
