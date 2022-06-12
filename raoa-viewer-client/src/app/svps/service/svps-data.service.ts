import {Injectable} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {Veranstaltung} from '../interfaces/veranstaltung';
import {firstValueFrom} from 'rxjs';
import {Startliste} from '../interfaces/startliste';
import {ServerApiService} from '../../service/server-api.service';
import {
    ListKnownCompetitorsGQL,
    ListKnownCompetitorsQuery,
    ListKnownCompetitorsQueryVariables
} from '../../generated/graphql';
import {Resultat, ResultatOverall, ResultatVeranstaltung} from '../interfaces/resultat';

@Injectable({
    providedIn: 'root'
})
export class SvpsDataService {

    private veranstaltungen: Map<number, Veranstaltung> = new Map<number, Veranstaltung>();
    private startlisten: Map<[number, number], Startliste> = new Map<[number, number], Startliste>();
    private veranstaltungResultate: Map<number, ResultatOverall> = new Map<number, ResultatOverall>();
    private resultate: Map<[number, number], Resultat> = new Map<[number, number], Resultat>();

    constructor(private httpClient: HttpClient,
                private serverApi: ServerApiService,
                private listKnownCompetitorsQuery: ListKnownCompetitorsGQL) {
    }

    public async fetchVeranstaltung(id: number): Promise<Veranstaltung> {
        if (this.veranstaltungen.has(id)) {
            return this.veranstaltungen.get(id);
        }
        const veranstaltung = await firstValueFrom(this.httpClient.get<Veranstaltung>(`https://info.fnch.ch/startlisten/${id}.json`));
        this.veranstaltungen.set(id, veranstaltung);
        return veranstaltung;
    }

    public async fetchStartlist(competitionId: number, listId: number): Promise<Startliste> {
        const key: [number, number] = [competitionId, listId];
        if (this.startlisten.has(key)) {
            return this.startlisten.get(key);
        }
        const startliste = await firstValueFrom(this.httpClient.get<Startliste>(`https://info.fnch.ch/startlisten/${competitionId}.json?startliste_id=${listId}`));
        this.startlisten.set(key, startliste);
        return startliste;
    }

    public async fetchResultat(id: number): Promise<ResultatOverall> {
        if (this.veranstaltungResultate.has(id)) {
            return this.veranstaltungResultate.get(id);
        }
        const veranstaltung = await firstValueFrom(this.httpClient.get<ResultatOverall>(`https://info.fnch.ch/resultate/veranstaltungen/${id}.json`));
        this.veranstaltungResultate.set(id, veranstaltung);
        return veranstaltung;

    }

    public async fetchResultate(competitionId: number, listId: number): Promise<Resultat> {
        const key: [number, number] = [competitionId, listId];
        if (this.resultate.has(key)) {
            return this.resultate.get(key);
        }
        const resultat = await firstValueFrom(this.httpClient.get<Resultat>(`https://info.fnch.ch/resultate/${competitionId}.json?pruefung_id=${listId}`));
        this.resultate.set(key, resultat);
        return resultat;
    }

    public async listKnownCompetitors(): Promise<Set<number>> {
        const groups = await
            this.serverApi.query<ListKnownCompetitorsQuery, ListKnownCompetitorsQueryVariables>
            (this.listKnownCompetitorsQuery, {});
        const ret = new Set<number>();
        groups.listGroups.forEach(group => {
            group.labels.forEach(label => {
                if (label.labelName === 'fnch-competitor-id') {
                    ret.add(Number.parseInt(label.labelValue, 10));
                }
            });
        });
        return ret;
    }
}
