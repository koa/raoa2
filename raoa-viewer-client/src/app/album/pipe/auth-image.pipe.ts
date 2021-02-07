import {Pipe, PipeTransform} from '@angular/core';
import {HttpClient} from '@angular/common/http';

@Pipe({
    name: 'authImage'
})
export class AuthImagePipe implements PipeTransform {

    constructor(private http: HttpClient) {
    }

    async transform(src: string): Promise<string> {
        const imageBlob = await this.http.get(src, {responseType: 'blob'}).toPromise();
        const reader = new FileReader();
        return new Promise((resolve, reject) => {
            reader.onloadend = () => resolve(reader.result as string);
            reader.readAsDataURL(imageBlob);
        });
    }

}
