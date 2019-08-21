import {Component, OnInit} from '@angular/core';
import {AuthenticationState, AuthenticationStateEnum} from '../../interfaces/authentication.state';

@Component({
  selector: 'app-request-access-header',
  templateUrl: './request-access-header.component.html',
  styleUrls: ['./request-access-header.component.css']
})
export class RequestAccessHeaderComponent implements OnInit {
  public authenticationState: AuthenticationState;
  AUTHENTICATED: AuthenticationStateEnum = 'AUTHENTICATED';
  AUTHORIZATION_REQUESTED: AuthenticationStateEnum = 'AUTHORIZATION_REQUESTED';

  constructor() {
  }

  ngOnInit() {
  }

}
