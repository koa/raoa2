import {Injectable} from '@angular/core';
import {AllAlbums, AllAlbumsGQL} from '../generated/graphql';
import {ApolloQueryResult} from 'apollo-client';
import {Apollo} from 'apollo-angular';
import {HttpLink} from 'apollo-angular-link-http';
import {SocialUser} from 'angularx-social-login';
import {ApolloLink} from 'apollo-link';
import {InMemoryCache} from 'apollo-cache-inmemory';
import {AppConfigService} from './app-config.service';

@Injectable({
  providedIn: 'root'
})
export class ServerApiService {
  private ready: boolean;
  private readyPromise: Promise<boolean>;
  private idToken: string;

  constructor(
    apollo: Apollo,
    httpLink: HttpLink,
    private appConfigService: AppConfigService,
    private albumListGQL: AllAlbumsGQL
  ) {
    this.ready = false;
    this.readyPromise = new Promise<boolean>(((resolve) => {
      const http = httpLink.create({uri: '/graphql'});

      appConfigService.getAuthService().then(authService => {
        authService.authState.subscribe((user: SocialUser) => {
          console.log(user);
          this.idToken = user.idToken;


          const authLink = new ApolloLink((operation, forward) => {

            console.log('Auth token: ' + this.idToken);

            // Use the setContext method to set the HTTP headers.
            operation.setContext({
              headers: {
                Authorization: this.idToken ? `Bearer ${this.idToken}` : ''
              }
            });

            // Call the next link in the middleware chain.
            return forward(operation);
          });

          apollo.create({
            link: authLink.concat(http),
            cache: new InMemoryCache()
          });
          resolve(true);
        });
      });
    }));


  }

  public async listAllAlbums(): Promise<ApolloQueryResult<AllAlbums.Query>> {
    if (this.ready || await this.readyPromise) {
      return this.albumListGQL.watch().valueChanges.toPromise();
    }
    return Promise.reject('Cannot init');
  }


}
