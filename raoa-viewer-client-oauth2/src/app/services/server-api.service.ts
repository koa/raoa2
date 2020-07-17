import {Injectable} from '@angular/core';
import {Maybe} from '../generated/graphql';
import * as Apollo from 'apollo-angular';
import {HttpLink} from 'apollo-angular-link-http';
import {InMemoryCache} from 'apollo-cache-inmemory';
import {AppConfigService} from './app-config.service';

@Injectable({
  providedIn: 'root'
})
export class ServerApiService {
  private ready: boolean;
  private readyPromise: Promise<boolean>;
  private idToken: string;
  private cache: InMemoryCache;


  constructor(
    apollo: Apollo.Apollo,
    httpLink: HttpLink,
    private appConfigService: AppConfigService,
  ) {
    this.ready = false;
    this.cache = new InMemoryCache();

    this.readyPromise = new Promise<boolean>(((resolve) => {
      appConfigService.takeCurrentUser().then(user => {
        const http = httpLink.create({uri: '/graphql'});
        apollo.create({
          link: http,
          cache: this.cache,
        });
        resolve(true);
      });

    }));
  }

  public async flushCache(): Promise<void> {
    return this.cache.reset();
  }

  public async query<T, V>(query: Apollo.Query<T, V>, variables: V): Promise<Maybe<T>> {
    if (!(this.ready || await this.readyPromise)) {
      return Promise.reject('Cannot init');
    }
    return new Promise<T>((resolve, reject) =>
      query.fetch(variables).subscribe(result => {
        if (result.data) {
          resolve(result.data);
        } else {
          reject(result.errors);
        }
      }, error => {
        reject(error);
      })
    );
  }

  public async update<T, V>(query: Apollo.Mutation<T, V>, variables: V): Promise<Maybe<T>> {
    if (!(this.ready || await this.readyPromise)) {
      return Promise.reject('Cannot init');
    }
    return new Promise<T>((resolve, reject) =>
      query.mutate(variables).subscribe(result => {
        if (result.data) {
          resolve(result.data);
        } else {
          reject('Error');
        }
      })
    );

  }
}
