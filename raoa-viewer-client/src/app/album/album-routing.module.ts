import {NgModule} from '@angular/core';
import {RouterModule, Routes} from '@angular/router';

import {ShowSingleMediaComponent} from './show-single-media/show-single-media.component';
import {AlbumPage} from './single-album/album.page';
import {AlbumListComponent} from './album-list/album-list.component';
import {ManageAlbumComponent} from './manage-album/manage-album.component';

const routes: Routes = [
    {path: '', component: AlbumListComponent},
    {
        path: ':id',
        component: AlbumPage
    },
    {
        path: ':id/manage',
        component: ManageAlbumComponent
    },
    {
        path: ':id/media/:mediaId',
        component: ShowSingleMediaComponent
    }
];

@NgModule({
    imports: [RouterModule.forChild(routes)],
    exports: [RouterModule],
})
export class AlbumPageRoutingModule {
}
