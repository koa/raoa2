export interface ResultatOverall {
    veranstaltung: ResultatVeranstaltung;
    pruefungen: ResultatPruefung[];
}

export interface ResultatVeranstaltung {
    datum: string;
    typ_code: string;
    ort: string;
}

export interface ResultatPruefung {
    id: number;
    nummer: string;
    datum: string;
    ausschreibung_kategorie_text: string;
}

export interface Resultat {
    id: number;
    kategorie_code: string;
    kategorie_text: string;
    resultate: ResultatZeile[];
    zwischenresultate: Zwischenresultat[];
}

export interface ResultatZeile {
    id: number;
    rang: number;
    klassiert: boolean;
    reiter_id: number;
    resultat_layout: string;
}

export interface Zwischenresultat {
    id: number;
    position: number;
    name: string;
    datum: string;
    uhrzeit: string;
    zeilen: ZwischenResultatZeile[];
}

export interface ZwischenResultatZeile {
    id: number;
    position: number;
    rang: number;
    reiter_id: number;
    resultat_layout: string;
}
