import {Component, NgZone, OnDestroy, OnInit} from '@angular/core';
import {AppConfigService} from '../../services/app-config.service';
import {ServerApiService} from '../../services/server-api.service';
import {AuthenticationState, RequestAccessMutationGQL, WelcomeUserInfoGQL} from '../../generated/graphql';
import {RequestAccessDialogComponent} from '../request-access-dialog/request-access-dialog.component';
import {MatDialog} from '@angular/material/dialog';
import {Router} from '@angular/router';

@Component({
  selector: 'app-welcome',
  templateUrl: './welcome.component.html',
  styleUrls: ['./welcome.component.css']
})
export class WelcomeComponent implements OnInit, OnDestroy {

  public requestAccessComment: string;

  public signedIn = false;
  public userName = '';
  public profilePhoto: string;
  public userEmail: string;
  public canManageUsers = false;
  public showRequestAuthorizationButton = false;
  public showRequestPendingInformation = false;
  private currentUser: gapi.auth2.GoogleUser;
  private runningTimeout: NodeJS.Timeout;

  constructor(public appConfigService: AppConfigService,
              private ngZone: NgZone,
              private serverApiService: ServerApiService,
              private welcomeUserQuery: WelcomeUserInfoGQL,
              private requestAccessMutationGQL: RequestAccessMutationGQL,
              private router: Router,
              private dialog: MatDialog
  ) {
  }

  ngOnDestroy(): void {
    this.cancelRunningTimeout();
  }

  ngOnInit() {
    this.evaluateUserState();
    this.appConfigService.renderButton('google-signin-button');
    this.appConfigService.signedInObservable.subscribe(signedIn => this.ngZone.run(() => this.signedIn = signedIn));
    this.appConfigService.currentUserObservable.subscribe(user => {
      this.ngZone.run(() => {
        this.currentUser = user;
        const basicProfile = user.getBasicProfile();
        if (basicProfile != null) {
          this.userName = basicProfile.getName();
          this.profilePhoto = basicProfile.getImageUrl();
          this.userEmail = basicProfile.getEmail();
        }
      });
      this.evaluateUserState();
    });
  }

  requestForAccess() {
    this.dialog.open(RequestAccessDialogComponent, {
      data: this.currentUser
    }).afterClosed().subscribe(() => {
      this.evaluateUserState();
    });
  }

  submitRequest() {
    this.serverApiService
      .update(this.requestAccessMutationGQL, {reason: this.requestAccessComment})
      .then(result => this.evaluateUserState());
  }

  private evaluateUserState() {
    this.serverApiService.flushCache();
    this.serverApiService
      .query(this.welcomeUserQuery, {})
      .then(userInfo => {
          if (userInfo != null) {
            this.ngZone.run(() => {
              if (userInfo.currentUser != null) {
                this.canManageUsers = userInfo.currentUser.canManageUsers;
              }
              if (userInfo.authenticationState != null) {
                this.showRequestAuthorizationButton =
                  userInfo.authenticationState === AuthenticationState.Authenticated;
                this.showRequestPendingInformation =
                  userInfo.authenticationState === AuthenticationState.AuthorizationRequested;
              }
            });
          }
          this.cancelRunningTimeout();
          if (userInfo.currentUser != null
            && userInfo.currentUser.newestAlbumCanAccess != null
            && userInfo.currentUser.newestAlbumCanAccess.id != null
          ) {
            this.ngZone.run(() => this.router.navigate(['/album', userInfo.currentUser.newestAlbumCanAccess.id]));
          } else {
            this.runningTimeout = setTimeout(() =>
                this.evaluateUserState(),
              1000,
            );
          }

        }
      );
  }

  private cancelRunningTimeout() {
    if (this.runningTimeout !== undefined) {
      clearTimeout(this.runningTimeout);
    }
  }
}
