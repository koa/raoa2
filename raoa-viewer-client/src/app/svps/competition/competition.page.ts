import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute, Params} from '@angular/router';
import {SvpsDataService} from '../service/svps-data.service';
import {Veranstaltung} from '../interfaces/veranstaltung';

@Component({
    selector: 'app-competition',
    templateUrl: './competition.page.html',
    styleUrls: ['./competition.page.scss'],
})
export class CompetitionPage implements OnInit {

    public competitionId: number = undefined;
    public mainData: Veranstaltung = undefined;
    public days: string[] = [];

    constructor(private route: ActivatedRoute, private dataService: SvpsDataService, private ngZone: NgZone) {
    }

    ngOnInit() {
        this.route.params.subscribe(async params => {
            this.competitionId = params.competitionId;
            const mainData = await this.dataService.fetchVeranstaltung(this.competitionId);
            this.ngZone.run(() => {
                this.mainData = mainData;
                const days: Set<string> = new Set();
                for (const startliste of mainData.startlisten) {
                    if (!startliste.hat_startzeiten) {
                        continue;
                    }
                    days.add(startliste.datum);
                }
                this.days = [];
                for (const day of days) {
                    this.days.push(day);
                }
                this.days.sort();
            });
        });
    }

}
