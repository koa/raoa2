import {Injectable} from '@angular/core';
import {ActivationStart, Router} from '@angular/router';
import {HeadlineDirective} from '../directive/headline.directive';
import {AuthenticationState, AuthenticationStateEnum} from '../interfaces/authentication.state';

@Injectable({
  providedIn: 'root'
})
export class FrontendBehaviorService {
  public title: string;
  public headline: HeadlineDirective;

  constructor(private router: Router) {
    this.router.events.forEach(event => {
      if (event instanceof ActivationStart) {
        this.title = '';
      }
    });
  }

  public processAuthenticationState(state: AuthenticationState, except: AuthenticationStateEnum[]) {
    if (except.find(e => e === state.state)) {
      return;
    }
    switch (state.state) {
      case AuthenticationStateEnum.UNKNOWN:
        window.location.pathname = '/login';
        break;
      case AuthenticationStateEnum.AUTHORIZED:
        this.router.navigate(['/']);
        break;
      case AuthenticationStateEnum.AUTHENTICATED:
      case AuthenticationStateEnum.AUTHORIZATION_REQUESTED:
        this.router.navigate(['/requestAccess']);
        break;
    }
  }


}
