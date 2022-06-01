// for Apollo Client v3:
import {ApolloLink, FetchResult, Observable, Operation} from '@apollo/client/core';
// or for Apollo Client v2:
// import { ApolloLink, Operation, FetchResult, Observable } from 'apollo-link'; // yarn add apollo-link
import {print} from 'graphql';
import {Client, ClientOptions, createClient} from 'graphql-ws';

export class MyWebSocketLink extends ApolloLink {
    private client: Client;

    constructor(options: ClientOptions) {
        super();
        this.client = createClient(options);
    }

    public request(operation: Operation): Observable<FetchResult> {
        return new Observable((sink) => {
            return this.client.subscribe<FetchResult>(
                {...operation, query: print(operation.query)},
                {
                    next: sink.next.bind(sink),
                    complete: sink.complete.bind(sink),
                    error: (err) => {
                        if (Array.isArray(err))
                            // GraphQLError[]
                        {
                            return sink.error(
                                new Error(err.map(({message}) => message).join(', ')),
                            );
                        }

                        if (err instanceof CloseEvent) {
                            return sink.error(
                                new Error(
                                    `Socket closed with event ${err.code} ${err.reason || ''}`,
                                    // reason will be available on clean closes only
                                ),
                            );
                        }

                        return sink.error(err);
                    },
                },
            );
        });
    }
}

