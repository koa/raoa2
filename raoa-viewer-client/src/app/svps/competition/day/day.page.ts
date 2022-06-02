import {Component, NgZone, OnInit} from '@angular/core';
import {ActivatedRoute} from '@angular/router';
import {SvpsDataService} from '../../service/svps-data.service';
import {Startliste, StartlisteZeile} from '../../interfaces/startliste';

@Component({
    selector: 'app-day',
    templateUrl: './day.page.html',
    styleUrls: ['./day.page.scss'],
})
export class DayPage implements OnInit {

    public rows: [Startliste, StartlisteZeile, boolean][] = [];

    constructor(private route: ActivatedRoute, private ngZone: NgZone, private dataService: SvpsDataService) {
    }

    ngOnInit() {
        this.route.params.subscribe(async params => {
            const competitionId: number = params.competitionId;
            const day: string = params.day;
            const veranstaltung = await this.dataService.fetchVeranstaltung(competitionId);
            const rows: [Startliste, StartlisteZeile, boolean][] = [];
            const konwnComptetitors = await this.dataService.listKownCompetitors();
            for (const startliste of veranstaltung.startlisten) {
                if (startliste.datum !== day || !startliste.hat_startzeiten) {
                    continue;
                }
                const liste = await this.dataService.fetchStartlist(competitionId, startliste.id);
                for (const startlisteZeile of liste.zeilen) {
                    if (startlisteZeile.typ !== 'starter' || startlisteZeile.kein_start) {
                        continue;
                    }
                    rows.push([startliste, startlisteZeile, konwnComptetitors.has(startlisteZeile.reiter_id)]);
                }
            }
            rows.sort((r1, r2) => {
                const t1 = r1[1].startzeit;
                const t2 = r2[1].startzeit;
                return t1.localeCompare(t2);
            });
            this.ngZone.run(() => {
                this.rows = rows;
            });
        });
    }

}
