import {Component, OnInit} from '@angular/core';
import {Apollo} from 'apollo-angular';
import gql from 'graphql-tag';
import {Router} from '@angular/router';

interface AuthenticationState {
  state: string;
  name: string;
  picture: string;
  email: string;
}

interface GraphQlResponseData {
  authenticationState: AuthenticationState;
}

const updateRequest = gql`mutation requestAccess($reason: String){
    requestAccess(comment: $reason){
        ok, result
    }
}
`;

@Component({
  selector: 'app-request-access',
  templateUrl: './request-access.component.html',
  styleUrls: ['./request-access.component.css']
})
export class RequestAccessComponent implements OnInit {
  loading = true;
  error: any;
  authenticationState: AuthenticationState;
  reason: string;

  constructor(private apollo: Apollo, private router: Router) {
  }

    ngOnInit() {
      this.apollo.watchQuery({
          query: gql`
              {
                  authenticationState {
                      state, name, picture, email
                  }
              }
          `
      }).valueChanges.subscribe(result => {
        // @ts-ignore
        const responseData: GraphQlResponseData = result.data;
        if (responseData) {
          switch (responseData.authenticationState.state) {
            case 'UNKNOWN':
              window.location.pathname = '/login';
              break;
            case 'AUTHORIZED':
              this.router.navigate(['/']);
              break;
          }
          this.authenticationState = responseData.authenticationState;
          this.loading = result.loading;
        } else {
          this.loading = result.loading;
          this.error = result.errors;
        }
      });
    }

  submitWithReason() {
    this.apollo.mutate({
      mutation: updateRequest,
      variables: {reason: this.reason}

    });
    console.log('Reason: ' + this.reason);
  }
}
