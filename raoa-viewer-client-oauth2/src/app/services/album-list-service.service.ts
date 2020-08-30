import {Injectable} from '@angular/core';
import {ServerApiService} from './server-api.service';
import {Album, ListAlbumsGQL} from '../generated/graphql';
import {NgxIndexedDBService} from 'ngx-indexed-db';

type ResultAlbum = { __typename?: 'Album' } & Pick<Album, 'id' | 'version' | 'name' | 'entryCount' | 'albumTime'>;

const STORE_ALBUM = 'album';

interface MutationResult {
  album: string;
  modified: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class AlbumListServiceService {

  constructor(private serverApiService: ServerApiService,
              private listAlbums: ListAlbumsGQL,
              private dbService: NgxIndexedDBService) {
    Promise.all(
      [dbService.getAll<ResultAlbum>(STORE_ALBUM).then(albums => albums.map(e => e.id)),
        serverApiService.query(listAlbums, {})])
      .then(values => {
        const existingIds = values[0];
        const loadedAlbums = values[1];
        return Promise.all(
          loadedAlbums.listAlbums.map(entry => {
            return dbService.getByKey<ResultAlbum>(STORE_ALBUM, entry.id).then<MutationResult>(storedData => {
              if (storedData == null) {
                // console.log('new: ' + entry.name);
                return dbService.add(STORE_ALBUM, entry).then(r => ({album: entry.id, modified: true})
                );
              } else if (storedData.version !== entry.version) {
                // console.log('modified: %o -> %o:' + entry.name, storedData, entry);
                return dbService.update(STORE_ALBUM, entry).then(r => ({album: entry.id, modified: true}));
              } else {
                return Promise.resolve({album: entry.id, modified: false});
              }
            });
          }))
          .then(updatedIds => {
            const keepIds = new Set(updatedIds.map(v => v.album));
            const removeIds = existingIds.filter(id => !keepIds.has(id));
            return Promise.all(
              removeIds.map(id => {
                dbService.delete(STORE_ALBUM, id);
              }))
              .then(removed => updatedIds.filter(v => v.modified).map(v => v.album));
          });
      }).then(r => console.log(r));
  }
}
