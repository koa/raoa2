import {Injectable} from '@angular/core';
import {
  AlbumContent,
  AlbumContentGQL,
  AlbumContentZipGQL,
  AllAlbums,
  AllAlbumsGQL,
  AuthenticationState,
  AuthenticationStateGQL,
  Maybe
} from '../generated/graphql';
import * as Apollo from 'apollo-angular';
//import {Apollo} from 'apollo-angular';
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


  constructor(
    apollo: Apollo.Apollo,
    httpLink: HttpLink,
    private appConfigService: AppConfigService,
    private albumListGQL: AllAlbumsGQL,
    private albumContentQGL: AlbumContentGQL,
    private albumContentZipGQL: AlbumContentZipGQL,
    private authenticationStateGQL: AuthenticationStateGQL
  ) {
    this.ready = false;
    this.readyPromise = new Promise<boolean>(((resolve) => {
      appConfigService.takeCurrentUser().then(user => {
        const http = httpLink.create({uri: '/graphql'});
        apollo.create({
          link: http,
          cache: new InMemoryCache(),
        });
        resolve(true);
      });

    }));
  }

  public async query<T, V>(query: Apollo.Query<T, V>, variables: V): Promise<Maybe<T>> {
    if (!(this.ready || await this.readyPromise)) {
      return Promise.reject('Cannot init');
    }
    return new Promise<T>((resolve, reject) =>
      query.watch(variables).valueChanges.subscribe(result => {
        if (result.data) {
          resolve(result.data);
        } else {
          reject(result.errors);
        }
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
          reject(result.errors);
        }
      })
    );

  }

  public async listAllAlbums(): Promise<Maybe<AllAlbums.ListAlbums>[]> {
    if (!(this.ready || await this.readyPromise)) {
      return Promise.reject('Cannot init');
    }
    return new Promise<AllAlbums.Query>((resolve, reject) =>
      this.albumListGQL.watch().valueChanges.subscribe(result => {
        if (result.data) {
          resolve(result.data);
        } else {
          reject(result.errors);
        }
      })
    ).then(result => {
      return result.listAlbums
        .filter(a => a.albumTime != null)
        .sort((a, b) => -a.albumTime.localeCompare(b.albumTime));
    });
  }

  public async listAlbumContent(albumId: string): Promise<AlbumContent.Query> {
    if (!(this.ready || await this.readyPromise)) {
      return Promise.reject('Cannot init');
    }
    return new Promise((resolve, reject) => {

      this.albumContentQGL.watch({albumId}).valueChanges.subscribe(result => {
        if (result.loading) {
          return;
        }
        if (result.data) {
          resolve(result.data);
        } else {
          reject(result.errors);
        }
      });
    });
  }

  public async getAuthenticationState(): Promise<AuthenticationState.Query> {
    if (!(this.ready || await this.readyPromise)) {
      return Promise.reject('Cannot init');
    }
    return new Promise((resolve, reject) => {
      this.authenticationStateGQL.watch().valueChanges.subscribe(result => {
        if (result.loading) {
          return;
        }
        const data: AuthenticationState.Query = result.data;
        if (data) {
          resolve(result.data);
        } else {
          reject(result.errors);
        }
      });
    });
  }

  public async getAlbumZipUri(albumId: string): Promise<string> {
    if (!(this.ready || await this.readyPromise)) {
      return Promise.reject('Cannot init');
    }
    return new Promise((resolve, reject) => {
      this.albumContentZipGQL.watch({albumId}).valueChanges.subscribe(result => {
        if (result.loading) {
          return;
        }
        if (result.data) {
          resolve(result.data.albumById.zipDownloadUri);
        } else {
          reject(result.errors);
        }
      });
    });
  }
}
