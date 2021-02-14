import {NgModule} from '@angular/core';
import {RouterModule, Routes} from '@angular/router';

import {AlbumPage} from './album.page';
import {ShowSingleMediaComponent} from './show-single-media/show-single-media.component';

const routes: Routes = [
    {
        path: '',
        component: AlbumPage
    },
    {
        path: 'media/:mediaId',
        component: ShowSingleMediaComponent
    }
];

@NgModule({
    imports: [RouterModule.forChild(routes)],
    exports: [RouterModule],
})
export class AlbumPageRoutingModule {
}
