import {ChangeDetectorRef, Component, OnDestroy, OnInit, ViewChild} from '@angular/core';
import {MediaMatcher} from '@angular/cdk/layout';
import {Router} from '@angular/router';
import {FrontendBehaviorService} from './services/frontend-behavior.service';
import {HeadlineDirective} from './directive/headline.directive';
import {Apollo} from 'apollo-angular';
import gql from 'graphql-tag';
import {ListAlbumEntry} from './interfaces/list-album.entry';
import {AuthenticationState} from './interfaces/authentication.state';

interface GraphQlResponseData {
  listAlbums: ListAlbumEntry[];
  authenticationState: AuthenticationState;
}

@Component({
  selector: 'app-root',
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnDestroy, OnInit {
  title = 'raoa-viewer-angular';
  mobileQuery: MediaQueryList;
  @ViewChild(HeadlineDirective, {static: true}) headline: HeadlineDirective;

  private mobileQueryListener: () => void;
  public albums: ListAlbumEntry[];
  public loading: boolean;
  public error: any;

  constructor(changeDetectorRef: ChangeDetectorRef,
              media: MediaMatcher,
              private router: Router,
              private frontendBehaviorService: FrontendBehaviorService,
              private apollo: Apollo) {
    this.mobileQuery = media.matchMedia('(max-width: 600px)');
    this.mobileQueryListener = () => changeDetectorRef.detectChanges();
    this.mobileQuery.addListener(this.mobileQueryListener);
  }

  ngOnDestroy(): void {
    this.mobileQuery.removeListener(this.mobileQueryListener);
  }

    ngOnInit(): void {
      this.frontendBehaviorService.headline = this.headline;
      this.apollo.watchQuery({
          query: gql`
              query getOverview {
                  listAlbums {
                      id, name, entryCount
                  }
                  authenticationState {
                      state
                      user{
                          canManageUsers
                      }
                  }
              }
          `
      }).valueChanges.subscribe(result => {
        // @ts-ignore
        const responseData: GraphQlResponseData = result.data;
        if (responseData) {
          this.frontendBehaviorService.processAuthenticationState(responseData.authenticationState, ['AUTHORIZED']);
          this.albums = responseData.listAlbums.sort((e1, e2) => e1.name.localeCompare(e2.name));
        }
        this.loading = result.loading;
        this.error = result.errors;
      });
    }
}
