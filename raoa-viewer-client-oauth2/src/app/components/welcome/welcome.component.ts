import {Component, NgZone, OnInit} from '@angular/core';
import {AppConfigService} from '../../services/app-config.service';
import {ServerApiService} from '../../services/server-api.service';
import {AuthenticationState, WelcomeUserInfoGQL} from '../../generated/graphql';
import {RequestAccessDialogComponent} from '../request-access-dialog/request-access-dialog.component';
import {MatDialog} from '@angular/material/dialog';

@Component({
  selector: 'app-welcome',
  templateUrl: './welcome.component.html',
  styleUrls: ['./welcome.component.css']
})
export class WelcomeComponent implements OnInit {
  public signedIn = false;
  public userName = '';
  public profilePhoto: string;
  public userEmail: string;
  public canManageUsers = false;
  public showRequestAuthorizationButton = false;
  public showRequestPendingInformation = false;
  private currentUser: gapi.auth2.GoogleUser;

  constructor(public appConfigService: AppConfigService,
              private ngZone: NgZone,
              private serverApiService: ServerApiService,
              private welcomeUserQuery: WelcomeUserInfoGQL,
              private dialog: MatDialog
  ) {
  }

  ngOnInit() {
    this.appConfigService.renderButton('sign-in-button')
    this.evaluateUserState();
    this.appConfigService.signedInObservable.subscribe(signedIn => {
      this.ngZone.run(() => this.signedIn = signedIn);
      console.log('signed in: ' + signedIn);
      if (signedIn) {
        this.ngZone.run(() => this.appConfigService.renderButton('re-sign-in-button'));
      }
    });
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

  private evaluateUserState() {
    this.serverApiService
      .query(this.welcomeUserQuery, {})
      .then(userInfo => {
          if (userInfo != null) {
            if (userInfo.currentUser != null) {
              this.canManageUsers = userInfo.currentUser.canManageUsers;
            }
            if (userInfo.authenticationState != null) {
              this.showRequestAuthorizationButton =
                userInfo.authenticationState === AuthenticationState.Authenticated;
              this.showRequestPendingInformation =
                userInfo.authenticationState === AuthenticationState.AuthorizationRequested;
            }
          }
        }
      );
  }
}
