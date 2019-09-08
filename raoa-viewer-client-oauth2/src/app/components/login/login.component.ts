import {Component, OnInit} from '@angular/core';
import {GoogleLoginProvider, SocialUser} from 'angularx-social-login';
import {AppConfigService} from '../../services/app-config.service';


@Component({
  selector: 'app-login',
  templateUrl: './login.component.html',
  styleUrls: ['./login.component.css']
})
export class LoginComponent implements OnInit {
  public user: SocialUser;
  public loggedIn: boolean;

  constructor(private configService: AppConfigService) {
  }

  async ngOnInit() {
    const authService = await this.configService.getAuthService();
    authService.authState.subscribe(user => {
      console.log('auth state');
      console.log(user);
      this.loggedIn = user != null;
      this.user = user;
    });
  }

  async signInWithGoogle() {
    const authService = await this.configService.getAuthService();
    authService.signIn(GoogleLoginProvider.PROVIDER_ID);
  }


  async signOut() {
    const authService = await this.configService.getAuthService();
    authService.signOut();
  }

}
