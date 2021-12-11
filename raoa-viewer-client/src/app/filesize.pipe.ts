import {Pipe, PipeTransform} from '@angular/core';

const prefixes: [number, string][] = [
    [1, ''],
    [1024, 'k'],
    [1024 * 1024, 'M'],
    [1024 * 1024 * 1024, 'G'],
    [1024 * 1024 * 1024 * 1024, 'T']
];

@Pipe({
    name: 'filesize'
})
export class FilesizePipe implements PipeTransform {
    transform(value: number): string {
        for (const prefix of prefixes) {
            const base = prefix[0];
            if (value < base * 10) {
                return (value / base).toFixed(1) + prefix[1];
            }
            if (value < base * 1024) {
                return (value / base).toFixed(0) + prefix[1];
            }
        }
        return value.toExponential(2);
    }
}
