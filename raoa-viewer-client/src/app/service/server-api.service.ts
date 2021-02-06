import {Injectable} from '@angular/core';
import {HttpLink} from 'apollo-angular/http';
import {LoginService} from './login.service';
import {ApolloLink, InMemoryCache} from '@apollo/client/core';
import {setContext} from '@apollo/client/link/context';
import {Maybe} from '../generated/graphql';
import * as Apollo from 'apollo-angular';

@Injectable({
    providedIn: 'root'
})
export class ServerApiService {
    private readyPromise: Promise<boolean>;
    private cache: InMemoryCache;

    constructor(
        apollo: Apollo.Apollo,
        httpLink: HttpLink,
        private login: LoginService
    ) {
        this.cache = new InMemoryCache();
        const auth: ApolloLink = setContext(async (_, {headers}) => {
            // Grab token if there is one in storage or hasn't expired
            const token = await this.login.idToken();
            // Return the headers as usual
            return {
                headers: {
                    Authorization: `Bearer ${token}`,
                },
            };
        });

        this.readyPromise =
            login.signedInUser().then(user => {
                const http = ApolloLink.from([auth, httpLink.create({uri: '/graphql'})]);
                // const http = httpLink.create({uri: '/graphql'});
                apollo.create({
                    link: http,
                    cache: this.cache,
                });
                this.readyPromise = Promise.resolve(true);
                return true;
            });
    }

    public async query<T, V>(query: Apollo.Query<T, V>, variables: V): Promise<Maybe<T>> {
        if (!(await this.readyPromise)) {
            return Promise.reject('Cannot init');
        }
        return new Promise<T>((resolve, reject) =>
            query.fetch(variables).subscribe(result => {
                if (result.data) {
                    resolve(result.data);
                } else {
                    reject(result.errors);
                }
            }, error => {
                reject(error);
            })
        );
    }
}
