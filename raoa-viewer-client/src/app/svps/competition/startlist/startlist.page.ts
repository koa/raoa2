import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute, Params} from '@angular/router';
import {SvpsDataService} from '../../service/svps-data.service';
import {Startliste} from '../../interfaces/startliste';
import {Veranstaltung} from '../../interfaces/veranstaltung';

@Component({
    selector: 'app-startlist',
    templateUrl: './startlist.page.html',
    styleUrls: ['./startlist.page.scss'],
})
export class StartlistPage implements OnInit {

    public competitionId: number = undefined;
    public listId: number = undefined;
    public data: Startliste = undefined;
    public competitionData: Veranstaltung = undefined;
    public showStartTime = false;
    public knownCompetitors: Set<number>;

    constructor(private route: ActivatedRoute, private ngZone: NgZone, private dataService: SvpsDataService) {
    }

    ngOnInit() {
        this.route.params.subscribe(async params => {
            this.competitionId = params.competitionId;
            this.listId = params.listId;
            const competitionData = await this.dataService.fetchVeranstaltung(this.competitionId);
            const listData = await this.dataService.fetchStartlist(this.competitionId, this.listId);
            const knownCompetitors = await this.dataService.listKnownCompetitors();
            this.showStartTime = listData.hat_startzeiten;
            this.ngZone.run(() => {
                this.data = listData;
                this.competitionData = competitionData;
                this.knownCompetitors = knownCompetitors;
            });
        });

    }

}
