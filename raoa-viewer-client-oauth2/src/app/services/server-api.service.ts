import {Injectable} from '@angular/core';
import {AlbumContent, AlbumContentGQL, AlbumContentZipGQL, AllAlbums, AllAlbumsGQL, Maybe} from '../generated/graphql';
import {Apollo} from 'apollo-angular';
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
    apollo: Apollo,
    httpLink: HttpLink,
    private appConfigService: AppConfigService,
    private albumListGQL: AllAlbumsGQL,
    private albumContentQGL: AlbumContentGQL,
    private albumContentZipGQL: AlbumContentZipGQL
  ) {
    this.ready = false;
    this.readyPromise = new Promise<boolean>(((resolve) => {
      appConfigService.login().then(user => {
        const http = httpLink.create({uri: '/graphql'});
        apollo.create({
          link: http,
          cache: new InMemoryCache(),
        });
        resolve(true);

      });

    }));
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
      return result.listAlbums.sort((a, b) => a.name.localeCompare(b.name));
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
