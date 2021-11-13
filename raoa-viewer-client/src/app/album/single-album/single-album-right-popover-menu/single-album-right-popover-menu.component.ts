import {Component, Input, OnInit} from '@angular/core';

@Component({
    selector: 'app-single-album-right-popover-menu',
    templateUrl: './single-album-right-popover-menu.component.html',
    styleUrls: ['./single-album-right-popover-menu.component.scss'],
})
export class SingleAlbumRightPopoverMenuComponent implements OnInit {

    @Input()
    public fnchCompetitionId: string | undefined = undefined;

    @Input() public selectMode = false;
    @Input()
    public onShowRanking = () => {
    }
    @Input() public toggleSelectMode = () => {
    }

    constructor() {
    }

    ngOnInit() {
    }

}
