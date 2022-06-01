export interface Startliste {
    id: number;
    position: number;
    nummer: string;
    name: string;
    datum: string;
    uhrzeit: string;
    programm_name: string;
    kategorie_text: string;
    hat_startzeiten: boolean;
    zeilen: [StartlisteZeile];
}

export interface StartlisteZeile {
    start_position: number;
    id: number;
    typ: 'text' | 'starter';
    reihenfolge: number;
    startnummer: number;
    kein_start: boolean;
    startzeit: string;
    reiter_id: number;
    reiter_ort: string;
    reiter_verein: string;
    weiteres: string;
    reiter_vorname: string;
    reiter_nachname: string;
    reiter_name: string;
    ist_fahren: true;
    pferde: [StartlistePferd];
    text: string;
}

export interface StartlistePferd {
    id: number;
    name: string;
    signalement: string;
    besitzer: string;
    zuechter: string;
}
