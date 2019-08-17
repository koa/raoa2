import {Component, OnInit} from '@angular/core';
import {Apollo} from 'apollo-angular';
import gql from 'graphql-tag';
import {Router} from '@angular/router';
import {AuthenticationState} from '../../interfaces/authentication.state';
import {FrontendBehaviorService} from '../../services/frontend-behavior.service';


interface GraphQlResponseData {
  authenticationState: AuthenticationState;
}

@Component({
  selector: 'app-album-list',
  templateUrl: './album-list.component.html',
  styleUrls: ['./album-list.component.css']
})
export class AlbumListComponent implements OnInit {

  loading = true;
  error: any;

  constructor(private apollo: Apollo,
              private router: Router,
              private frontendBehaviorService: FrontendBehaviorService
  ) {
  }

    ngOnInit() {
      this.apollo.watchQuery({
          query: gql`
              {
                  authenticationState {
                      state
                  }
              }
          `
      }).valueChanges.subscribe(result => {
        // @ts-ignore
        const responseData: GraphQlResponseData = result.data;
        if (responseData) {
          this.frontendBehaviorService.processAuthenticationState(responseData.authenticationState, ['AUTHORIZED']);
        } else {
          this.loading = result.loading;
          this.error = result.errors;
        }
      });
    }

}
