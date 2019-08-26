import {Component, ComponentFactoryResolver, OnInit, ViewContainerRef} from '@angular/core';
import gql from 'graphql-tag';
import {Router} from '@angular/router';
import {FrontendBehaviorService} from '../../services/frontend-behavior.service';
import {AuthenticationState, AuthenticationStateEnum} from '../../interfaces/authentication.state';
import {RequestAccessHeaderComponent} from '../request-access-header/request-access-header.component';
import {ApolloService} from '../../services/apollo.service';

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
  reason: string;
  authenticationState: AuthenticationState;
  AUTHORIZED: AuthenticationStateEnum = 'AUTHORIZED';
  AUTHENTICATED: AuthenticationStateEnum = 'AUTHENTICATED';
  AUTHORIZATION_REQUESTED: AuthenticationStateEnum = 'AUTHORIZATION_REQUESTED';

  constructor(
    private apolloService: ApolloService,
    private router: Router,
    private frontendBehaviorService: FrontendBehaviorService,
    private componentFactoryResolver: ComponentFactoryResolver) {
  }

    ngOnInit() {
      this.apolloService.query().watchQuery({
          query: gql`
              query userData{
                  authenticationState {
                      state,
                      user {
                          info{
                              email, name, picture
                          }
                      }
                  }
              }
          `
      }).valueChanges.subscribe(result => {
        // @ts-ignore
        const responseData: GraphQlResponseData = result.data;
        if (responseData) {
          this.frontendBehaviorService.processAuthenticationState(responseData.authenticationState,
            [this.AUTHORIZATION_REQUESTED, this.AUTHENTICATED]);

          this.frontendBehaviorService.title = 'Request Access';
          const ref: ViewContainerRef = this.frontendBehaviorService.headline.viewContainerRef;
          ref.clear();
          const component = ref.createComponent(
            this.componentFactoryResolver.resolveComponentFactory(RequestAccessHeaderComponent));
          component.instance.authenticationState = responseData.authenticationState;
          this.authenticationState = responseData.authenticationState;


          this.loading = result.loading;
        } else {
          this.loading = result.loading;
          this.error = result.errors;
        }
      });
    }

  submitWithReason() {
    this.apolloService.update().mutate({
      mutation: updateRequest,
      variables: {reason: this.reason}

    }).toPromise().then(r => {
      this.ngOnInit();
    });

  }
}
